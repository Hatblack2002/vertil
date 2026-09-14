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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * Runtime real usando ONNX Runtime Android (com.microsoft.onnxruntime:onnxruntime-android).
 *
 * Estado: IMPLEMENTADO.
 *
 * Carga cualquier modelo .onnx y ejecuta inferencia. Para LLM reales en formato
 * ONNX se requiere un tokenizador específico (BPE/SentencePiece) que v1.1 integrará.
 * Esta implementación hace tokenización whitespace simple para validación de
 * que la inferencia ejecuta sin errores.
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
    private var inputNames: List<String> = emptyList()
    private var outputNames: List<String> = emptyList()

    @Volatile private var cancelled = false

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
                val file = File(info.filePath)
                if (!file.exists() || !file.canRead()) {
                    state = ModelState.ERROR
                    return@withContext VertilResult.Failure(
                        message = "Archivo no accesible: ${info.filePath}",
                        module = "OnnxRuntime",
                        code = "FILE_NOT_FOUND"
                    )
                }

                val environment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val sess = environment.createSession(file.absolutePath, sessionOptions)

                env = environment
                session = sess
                inputNames = sess.inputNames.toList()
                outputNames = sess.outputNames.toList()

                VertilLog.i("OnnxRuntime", "Modelo cargado: ${info.name} (${info.sizeHuman})")
                VertilLog.i("OnnxRuntime", "Inputs: $inputNames / Outputs: $outputNames")

                _capabilities = ModelCapabilities(
                    supportsChat = true,
                    supportsEmbeddings = false,
                    supportsStreaming = false,
                    supportsToolCalling = false,
                    maxContextLength = info.contextLength ?: 2048,
                    maxOutputTokens = 1024
                )

                state = ModelState.READY
                val newInfo = info.copy(state = ModelState.READY)
                this@OnnxModelRuntime.info = newInfo
                VertilResult.ok(newInfo)
            } catch (oom: OutOfMemoryError) {
                state = ModelState.ERROR
                VertilResult.Failure(
                    message = "Memoria insuficiente para cargar el modelo. Libera RAM o usa un modelo más pequeño.",
                    cause = oom,
                    module = "OnnxRuntime",
                    code = "OOM"
                )
            } catch (t: Throwable) {
                state = ModelState.ERROR
                VertilResult.Failure(
                    message = "No se pudo cargar el modelo: ${t.message}",
                    cause = t,
                    module = "OnnxRuntime",
                    code = "LOAD_FAILED"
                )
            }
        }
    }

    override suspend fun unload(): VertilResult<Unit> = withContext(Dispatchers.IO) {
        try {
            state = ModelState.UNLOADING
            session?.close()
            session = null
            info = null
            state = ModelState.INSTALLED
            VertilLog.i("OnnxRuntime", "Modelo descargado")
            VertilResult.ok(Unit)
        } catch (t: Throwable) {
            VertilResult.fail("Unload failed: ${t.message}", t, "OnnxRuntime")
        }
    }

    override suspend fun generate(params: GenerationParams): VertilResult<GenerationResult> {
        val sess = session ?: return VertilResult.fail(
            "Modelo no cargado", module = "OnnxRuntime", code = "NOT_LOADED"
        )
        val environment = env ?: return VertilResult.fail(
            "Environment no disponible", module = "OnnxRuntime"
        )
        return withContext(Dispatchers.IO) {
            try {
                state = ModelState.GENERATING
                cancelled = false
                val start = System.currentTimeMillis()

                val inputs = mutableMapOf<String, OnnxTensor>()
                val firstInput = inputNames.firstOrNull()
                    ?: run {
                        state = ModelState.READY
                        return@withContext VertilResult.fail(
                            "El modelo no tiene inputs declarados", module = "OnnxRuntime"
                        )
                    }

                val fullPrompt = params.systemPrompt + "\n\n" + params.prompt

                // Estrategia: tokenización whitespace simple → input_ids LongArray
                val tokens = fullPrompt.split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .map { word -> ((word.hashCode() and 0x7FFFFFFF) % 30000).toLong() }
                    .toLongArray()
                val shape = longArrayOf(1, tokens.size.toLong())
                inputs[firstInput] = OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), shape)
                if (inputNames.size >= 2) {
                    val mask = LongArray(tokens.size) { 1L }
                    inputs[inputNames[1]] = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)
                }

                val results = sess.run(inputs)
                val elapsed = System.currentTimeMillis() - start

                val firstOutputName = outputNames.firstOrNull()
                val outputTensor = if (firstOutputName != null) results.get(firstOutputName) else null
                // Usar toString del tensor para evitar problemas de opt-in con .value
                val text: String = try {
                    val v = (outputTensor as? OnnxTensor)?.value
                    when (v) {
                        is Array<*> -> v.joinToString("\n") { x -> x?.toString() ?: "" }
                        is String -> v
                        is LongArray -> v.joinToString(" ") { x -> x.toString() }
                        is IntArray -> v.joinToString(" ") { x -> x.toString() }
                        is FloatArray -> v.joinToString(" ") { x -> "%.2f".format(x) }
                        is DoubleArray -> v.joinToString(" ") { x -> "%.2f".format(x) }
                        else -> v?.toString() ?: "(salida vacía)"
                    }
                } catch (_: Throwable) {
                    outputTensor?.toString() ?: "(salida vacía)"
                }

                // Liberar tensores
                inputs.values.forEach { t -> t.close() }
                (outputTensor as? OnnxTensor)?.close()
                results.close()

                state = ModelState.READY
                val tokensCount = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                val tps = if (elapsed > 0) tokensCount * 1000f / elapsed else null

                VertilResult.ok(
                    GenerationResult(
                        text = text,
                        tokensGenerated = tokensCount,
                        durationMs = elapsed,
                        tokensPerSecond = tps
                    )
                )
            } catch (t: Throwable) {
                state = ModelState.READY
                VertilResult.fail(
                    "Inferencia fallida: ${t.message}",
                    t, "OnnxRuntime", "INFERENCE_FAILED"
                )
            }
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun getRuntimeInfo(): Map<String, String> {
        val i = info ?: return mapOf("Runtime" to "ONNX (sin modelo)")
        return buildMap {
            put("Runtime", "ONNX Runtime Android")
            put("Format", "ONNX")
            put("Quantization", i.quantization ?: "—")
            put("Context", (i.contextLength ?: 2048).toString())
            put("Threads", "—")
            put("RAM", ModelInfo.humanSize(i.sizeBytes))
            put("Inputs", inputNames.joinToString(", "))
            put("Outputs", outputNames.joinToString(", "))
        }
    }
}
