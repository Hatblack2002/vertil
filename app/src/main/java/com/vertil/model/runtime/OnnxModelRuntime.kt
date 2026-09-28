package com.vertil.model.runtime

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.model.GenerationParams
import com.vertil.model.GenerationResult
import com.vertil.model.LocalModel
import com.vertil.model.ModelCapabilities
import com.vertil.model.ModelFormat
import com.vertil.model.ModelInfo
import com.vertil.model.ModelState
import com.vertil.model.packaging.ModelPackage
import com.vertil.model.packaging.ModelPackageLoader
import com.vertil.model.runtime.engine.ChatTemplateEngine
import com.vertil.model.runtime.engine.ExecutionPlan
import com.vertil.model.runtime.engine.ChatTurn
import com.vertil.model.runtime.engine.ExecutionPlanner
import com.vertil.model.runtime.engine.OnnxLmEngine
import com.vertil.tokenizer.HfConfigs
import com.vertil.tokenizer.HfTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runtime ONNX real sobre el motor genérico (especificación FASE A):
 *
 * ```
 * ModelInfo → ModelPackage (multi-archivo)
 *           → HfTokenizer (tokenizer.json real)
 *           → configs (config.json / generation_config.json / tokenizer_config.json)
 *           → OrtSession → ExecutionPlanner (inspección dinámica) → ExecutionPlan
 *           → ChatTemplateEngine (chat_template real) → prompt
 *           → OnnxLmEngine (prefill → greedy → KV cache → EOS) → texto real
 * ```
 *
 * NO hay tokenización aproximada (hash/whitespace): la única vía de generación
 * es tokenizer real → ONNX real → detokenización real. La prueba con tensores
 * cero NO forma parte de este runtime: sigue existiendo SOLO como validación
 * de importación (OnnxRuntimeInspector.validateOpenable).
 */
class OnnxModelRuntime : LocalModel {

    override val runtimeId: String = "onnx"
    override var info: ModelInfo? = null
        private set
    override var state: ModelState = ModelState.INSTALLED
        private set

    override val capabilities: ModelCapabilities
        get() = _capabilities ?: ModelCapabilities()
    private var _capabilities: ModelCapabilities? = null

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var plan: ExecutionPlan? = null
    private var engine: OnnxLmEngine? = null
    private var tokenizer: HfTokenizer? = null
    private var tokenizerConfig: HfConfigs.TokenizerConfig? = null
    private var loadedPackage: ModelPackage? = null

    /** Listener de streaming (texto acumulado) registrado por la capa superior. */
    @Volatile private var streamListener: ((String) -> Unit)? = null

    override fun setStreamListener(listener: ((String) -> Unit)?) {
        streamListener = listener
    }

    // ============================ CARGA ============================

    override suspend fun load(info: ModelInfo): VertilResult<ModelInfo> {
        if (info.format != ModelFormat.ONNX) {
            return VertilResult.fail(
                "OnnxRuntime solo soporta formato ONNX (recibió ${info.format.label})",
                module = "OnnxRuntime"
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                state = ModelState.LOADING
                // 1. PAQUETE (§4): descubrimos archivos; error claro si falta uno obligatorio.
                val pkgResult = ModelPackageLoader.discover(File(info.filePath))
                val pkg = when (pkgResult) {
                    is VertilResult.Success -> pkgResult.value
                    is VertilResult.Failure -> {
                        state = ModelState.ERROR
                        return@withContext VertilResult.Failure(
                            message = pkgResult.message,
                            cause = pkgResult.cause,
                            module = "OnnxRuntime",
                            code = pkgResult.code
                        )
                    }
                }
                VertilLog.i(TAG, ModelPackageLoader.describe(pkg))

                // 2. TOKENIZER REAL (§5) desde tokenizer.json del paquete.
                val tok = try {
                    HfTokenizer.fromJson(pkg.tokenizerFile.readText())
                } catch (t: Throwable) {
                    state = ModelState.ERROR
                    return@withContext VertilResult.fail(
                        "No se pudo interpretar ${pkg.tokenizerFile.name}: ${t.message}",
                        t, "OnnxRuntime", "TOKENIZER_INVALID"
                    )
                }

                // 3. CONFIGS (§3: nada hardcodeado — todo desde los archivos).
                val modelConfig = pkg.configFile?.readText()?.let { HfConfigs.parseModelConfig(it) }
                val genConfig = pkg.generationConfigFile?.readText()?.let { HfConfigs.parseGenerationConfig(it) }
                val tConfig = pkg.tokenizerConfigFile?.readText()?.let { HfConfigs.parseTokenizerConfig(it) }

                // 4. SESIÓN ORT.
                val environment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    try {
                        setIntraOpNumThreads(threads)
                    } catch (_: Throwable) { /* opcional */ }
                    // Modelos con past_key_values y shapes dinámicas: los memory
                    // patterns de ORT suponen formas estables entre runs y en
                    // este escenario provocan errores intermitentes
                    // ("invalid expand shape"). Desactivados (recomendación de
                    // HF optimum para decoders con KV cache).
                    try {
                        setMemoryPatternOptimization(false)
                    } catch (_: Throwable) { /* opcional */ }
                }
                val sess = try {
                    environment.createSession(pkg.onnxFile.absolutePath, sessionOptions)
                } catch (oom: OutOfMemoryError) {
                    state = ModelState.ERROR
                    return@withContext VertilResult.Failure(
                        message = "Memoria insuficiente para cargar el modelo. Libera RAM o usa un modelo más pequeño.",
                        cause = oom, module = "OnnxRuntime", code = "OOM"
                    )
                } catch (t: Throwable) {
                    state = ModelState.ERROR
                    return@withContext VertilResult.fail(
                        "No se pudo abrir el grafo ONNX: ${t.message}",
                        t, "OnnxRuntime", "LOAD_FAILED"
                    )
                }

                // 5. PLAN DE EJECUCIÓN (§7/§8): inspección dinámica del grafo.
                val planResult = ExecutionPlanner.plan(sess, modelConfig)
                val execPlan = when (planResult) {
                    is VertilResult.Success -> planResult.value
                    is VertilResult.Failure -> {
                        runCatching { sess.close() }
                        state = ModelState.ERROR
                        return@withContext VertilResult.Failure(
                            message = planResult.message,
                            cause = planResult.cause,
                            module = "OnnxRuntime",
                            code = planResult.code
                        )
                    }
                }

                // 6. MOTOR (§9-§13): greedy, EOS desde configs, límite de contexto.
                val eosIdFromTokenizer = tConfig?.eosToken?.let { tok.tokenToId(it) }
                val engineCfg = OnnxLmEngine.defaultConfig(genConfig, modelConfig, eosIdFromTokenizer)

                env = environment
                session = sess
                plan = execPlan
                tokenizer = tok
                tokenizerConfig = tConfig
                loadedPackage = pkg
                val eng = OnnxLmEngine(environment, sess, execPlan, modelConfig, tok)
                eng.configure(engineCfg)
                engine = eng

                VertilLog.i(TAG, "Modelo cargado: ${info.name} (${info.sizeHuman})")
                VertilLog.i(TAG, "EOS ids: ${engineCfg.eosTokenIds} · maxNewTokens=${engineCfg.maxNewTokens} " +
                    "· contextLimit=${engineCfg.contextLimit ?: "—"}")

                _capabilities = ModelCapabilities(
                    supportsChat = true,
                    supportsEmbeddings = false,
                    supportsStreaming = true,
                    supportsToolCalling = false,
                    maxContextLength = engineCfg.contextLimit ?: info.contextLength ?: 2048,
                    maxOutputTokens = engineCfg.maxNewTokens
                )

                state = ModelState.READY
                val newInfo = info.copy(state = ModelState.READY)
                this@OnnxModelRuntime.info = newInfo
                VertilResult.ok(newInfo)
            } catch (t: Throwable) {
                state = ModelState.ERROR
                VertilResult.fail(
                    "No se pudo cargar el modelo: ${t.message}",
                    t, "OnnxRuntime", "LOAD_FAILED"
                )
            }
        }
    }

    override suspend fun unload(): VertilResult<Unit> = withContext(Dispatchers.IO) {
        try {
            state = ModelState.UNLOADING
            streamListener = null
            engine?.cancel()
            session?.close()
            session = null
            plan = null
            engine = null
            tokenizer = null
            tokenizerConfig = null
            loadedPackage = null
            info = null
            state = ModelState.INSTALLED
            VertilLog.i(TAG, "Modelo descargado y recursos liberados")
            VertilResult.ok(Unit)
        } catch (t: Throwable) {
            VertilResult.fail("Unload failed: ${t.message}", t, "OnnxRuntime")
        }
    }

    // ============================ GENERACIÓN ============================

    override suspend fun generate(params: GenerationParams): VertilResult<GenerationResult> {
        val eng = engine ?: return VertilResult.fail(
            "Modelo no cargado", module = "OnnxRuntime", code = "NOT_LOADED"
        )
        val tok = tokenizer ?: return VertilResult.fail(
            "Tokenizer no disponible", module = "OnnxRuntime", code = "NOT_LOADED"
        )

        return withContext(Dispatchers.IO) {
            try {
                state = ModelState.GENERATING

                // 1. Mensajes: system + historial + usuario (§6).
                val messages = ArrayList<ChatTurn>(params.history.size + 2)
                if (params.systemPrompt.isNotBlank()) {
                    messages.add(ChatTurn("system", params.systemPrompt))
                }
                messages.addAll(params.history)
                messages.add(ChatTurn("user", params.prompt))

                // 2. CHAT TEMPLATE real desde tokenizer_config.json.
                val promptBuild = ChatTemplateEngine.buildPrompt(messages, tokenizerConfig, tok)

                // 3. TOKENIZACIÓN real (add_special_tokens=false, igual que
                //    apply_chat_template de HF: los especiales ya están en el texto).
                val promptIds = tok.encode(promptBuild.prompt, addSpecialTokens = false)
                VertilLog.i(TAG, "Prompt (${promptBuild.source}): ${promptIds.size} ids " +
                    "[${promptIds.take(8).joinToString()}…]")

                // 4. MOTOR autorregresivo (greedy + KV cache + EOS).
                val maxNew = params.maxNewTokens ?: params.maxTokens
                val result = eng.generate(
                    OnnxLmEngine.Request(
                        promptIds = promptIds,
                        maxNewTokensOverride = maxNew,
                        onToken = { cumulative, _ -> streamListener?.invoke(cumulative) },
                        isCancelled = { cancelled }
                    )
                )
                when (result) {
                    is VertilResult.Success -> {
                        val out = result.value
                        // anexar métricas técnicas a runtime info (§20)
                        lastMetrics = mapOf(
                            "prefillMs" to out.prefillMs.toString(),
                            "firstTokenMs" to (out.firstTokenMs?.toString() ?: "—"),
                            "finishReason" to out.finishReason.name
                        )
                        VertilResult.ok(
                            GenerationResult(
                                text = out.text,
                                tokensGenerated = out.tokensGenerated,
                                durationMs = out.durationMs,
                                tokensPerSecond = out.tokensPerSecond
                            )
                        )
                    }
                    is VertilResult.Failure -> result
                }
            } finally {
                state = ModelState.READY
            }
        }
    }

    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
        engine?.cancel()
    }

    override fun getRuntimeInfo(): Map<String, String> {
        val i = info ?: return mapOf("Runtime" to "ONNX (sin modelo)")
        return buildMap {
            put("Runtime", "ONNX Runtime Android (motor genérico)")
            put("Format", "ONNX")
            put("Paquete", loadedPackage?.name ?: "—")
            put("Tokenizer", loadedPackage?.tokenizerFile?.name ?: "—")
            put("ChatTemplate", tokenizerConfig?.chatTemplate?.let { "tokenizer_config.json" } ?: "fallback ChatML")
            put("Plan", plan?.toString() ?: "—")
            put("KV cache", if (plan?.supportsKvCache == true) "sí" else "no")
            put("Quantization", i.quantization ?: "—")
            put("Context", (i.contextLength ?: 2048).toString())
            put("RAM", ModelInfo.humanSize(loadedPackage?.totalSizeBytes ?: i.sizeBytes))
            lastMetrics.forEach { (k, v) -> put(k, v) }
        }
    }

    @Volatile private var lastMetrics: Map<String, String> = emptyMap()

    companion object {
        private const val TAG = "OnnxRuntime"
    }
}
