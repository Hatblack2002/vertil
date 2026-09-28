package com.vertil.model.runtime.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.tokenizer.HfConfigs
import com.vertil.tokenizer.HfTokenizer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motor genérico de generación autorregresiva (especificación §9–§17).
 *
 * Flujo por paso:
 * ```
 * prompt → tokenizer → input_ids → attention_mask → position_ids
 *        → KV cache inicial (vacío) → ONNX forward → logits[last]
 *        → argmax (greedy) → actualizar KV cache → siguiente token → …
 *        → EOS / maxNewTokens / cancelación → detokenización real
 * ```
 *
 * Garantías:
 *  - NO hardcodea ningún modelo: todo proviene de [ExecutionPlan] (grafo real),
 *    [HfTokenizer] (tokenizer.json) y [HfConfigs] (config.json /
 *    generation_config.json);
 *  - KV cache sin copias: los tensores `present.*` de un paso se reutilizan
 *    como `past_key_values.*` del siguiente (una sola generación viva);
 *  - greedy puro (argmax) — sin sampling en v1 (especificación §12);
 *  - cancelable en cada paso ([Request.isCancelled]);
 *  - streaming ([Request.onToken] con texto acumulado decodificado);
 *  - los recursos ORT se liberan en todo camino de salida (try/finally).
 *
 * Grafos SIN KV cache: se re-alimenta la secuencia completa en cada paso
 * (lento pero correcto — compatibilidad genérica).
 */
class OnnxLmEngine(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val plan: ExecutionPlan,
    private val modelConfig: HfConfigs.ModelConfig?,
    private val tokenizer: HfTokenizer
) {

    /** Configuración del motor (derivada de los archivos del paquete). */
    data class EngineConfig(
        /** EOS: de generation_config.json → config.json → tokenizer_config. */
        val eosTokenIds: Set<Int> = emptySet(),
        /** Límite de tokens generados (evita generación infinita — §13). */
        val maxNewTokens: Int = 256,
        /** Límite total de contexto (max_position_embeddings) o null. */
        val contextLimit: Int? = null
    )

    data class Request(
        /** Ids del prompt YA renderizado con el chat template. */
        val promptIds: List<Int>,
        /** Límite por-petición (UI). null = usa el configurado en el engine. */
        val maxNewTokensOverride: Int? = null,
        val onToken: ((cumulativeText: String, tokensGenerated: Int) -> Unit)? = null,
        val isCancelled: () -> Boolean = { false }
    )

    enum class FinishReason { EOS, MAX_NEW_TOKENS, CONTEXT_FULL, CANCELLED }

    data class Output(
        val text: String,
        val ids: List<Int>,
        val tokensGenerated: Int,
        val durationMs: Long,
        val firstTokenMs: Long?,
        val tokensPerSecond: Float?,
        val prefillMs: Long,
        val finishReason: FinishReason
    )

    private var engineConfig: EngineConfig = EngineConfig()

    fun configure(cfg: EngineConfig) { engineConfig = cfg }

    /** Marca de cancelación compartida (la UI/ViewModel pone true). */
    val cancelFlag = AtomicBoolean(false)

    fun cancel() { cancelFlag.set(true) }

    // ============================ GENERACIÓN ============================

    fun generate(request: Request): VertilResult<Output> {
        if (request.promptIds.isEmpty()) {
            return VertilResult.fail(
                "El prompt quedó vacío tras la tokenización.",
                module = TAG, code = "EMPTY_PROMPT"
            )
        }
        cancelFlag.set(false)
        val started = System.currentTimeMillis()
        val maxNewTokens = request.maxNewTokensOverride ?: engineConfig.maxNewTokens
        var promptTensors: MutableMap<String, OnnxTensor>? = null
        var result: OrtSession.Result? = null
        // Caches creados por nosotros en el prefill (longitud 0) — se liberan
        // justo tras el primer forward.
        var zeroCaches: List<OnnxTensor> = emptyList()

        try {
            val promptLen = request.promptIds.size

            // ================= PREFILL =================
            val prefillCache = buildZeroLengthCaches()
            zeroCaches = prefillCache.second
            val prefillTensors = buildSequenceTensors(
                ids = request.promptIds,
                pastLen = 0
            ).toMutableMap()
            promptTensors = prefillTensors
            if (plan.supportsKvCache) {
                plan.cacheInputs.forEachIndexed { i, name ->
                    prefillTensors[name] = prefillCache.first[i]
                }
            }

            var seqLen = promptLen
            result = runSession(prefillTensors, "prefill")
            prefillTensors.values.forEach { closeQuietly(it) }
            promptTensors = null
            zeroCaches = emptyList() // los tensores de cache de longitud 0 ya no viven
            val prefillMs = System.currentTimeMillis() - started

            val generated = ArrayList<Int>(maxNewTokens.coerceAtMost(1024))
            var firstTokenMs: Long? = null
            var finishReason: FinishReason? = null
            var cumulativeText = ""
            var prevResult: OrtSession.Result? = null

            while (true) {
                // --- selección greedy sobre logits de la ÚLTIMA posición ---
                val nextId = argmaxLastPosition(result ?: run {
                    return@generate VertilResult.fail(
                        "Sesión terminada inesperadamente.", module = TAG, code = "SESSION_CLOSED"
                    )
                })
                    ?: return@generate VertilResult.fail(
                        "No se pudieron leer los logits del modelo.",
                        module = TAG, code = "LOGITS_READ_FAILED"
                    )

                if (nextId in engineConfig.eosTokenIds) {
                    finishReason = FinishReason.EOS
                    break
                }
                generated.add(nextId)

                val now = System.currentTimeMillis()
                if (firstTokenMs == null) firstTokenMs = now - started

                // --- detokenización incremental (real, del tokenizer) ---
                cumulativeText = tokenizer.decode(generated, skipSpecialTokens = true)
                request.onToken?.invoke(cumulativeText, generated.size)

                // --- condiciones de parada ---
                if (request.isCancelled() || cancelFlag.get()) {
                    finishReason = FinishReason.CANCELLED; break
                }
                if (generated.size >= maxNewTokens) {
                    finishReason = FinishReason.MAX_NEW_TOKENS; break
                }
                val limit = engineConfig.contextLimit
                if (limit != null && seqLen + 1 >= limit) {
                    finishReason = FinishReason.CONTEXT_FULL; break
                }

                // ================= DECODE STEP =================
                // Ciclo de vida: prevResult (k-2) se cierra DESPUÉS del run que
                // consumió sus tensores present.*; los tensores que creamos
                // nosotros (owned) se cierran tras el run; los borrowed
                // (present.* de prevResult) SOLO se liberan vía prevResult.close()
                // — NUNCA dos veces (doble free corrompería el arena de ORT).
                prevResult?.let { closeQuietly(it) }
                prevResult = result
                result = null

                val cacheTensors = if (plan.supportsKvCache) {
                    // Reutilización SIN copia: outputs present.* del paso previo
                    plan.cacheInputs.zip(plan.cacheOutputs).map { (inName, outName) ->
                        val t = tensorOf(prevResult!!, outName) ?: throw IllegalStateException(
                            "El grafo no devolvió la salida de cache '$outName'"
                        )
                        inName to t
                    }
                } else null

                val ownedTensors = buildSequenceTensors(
                    ids = listOf(nextId),
                    pastLen = seqLen
                )
                val runMap = HashMap<String, OnnxTensor>(ownedTensors.size + (cacheTensors?.size ?: 0))
                runMap.putAll(ownedTensors)
                cacheTensors?.forEach { (name, tensor) -> runMap[name] = tensor }

                val newResult = runSession(runMap, "decode@${generated.size}")
                // Solo los PROPIOS (tokens/máscara/posiciones) se cierran aquí;
                // los borrowed (present.*) viven dentro de prevResult.
                ownedTensors.values.forEach { closeQuietly(it) }
                result = newResult
                seqLen += 1
            }
            closeQuietly(prevResult)
            closeQuietly(result)
            result = null

            val duration = System.currentTimeMillis() - started
            val tokens = generated.size
            val tps = if (duration > 0 && tokens > 0) tokens * 1000f / duration else null
            val reason = finishReason ?: FinishReason.MAX_NEW_TOKENS
            VertilLog.i(TAG, "Generación: $tokens tokens en ${duration}ms (${reason}) " +
                "[prefill=${prefillMs}ms, firstToken=${firstTokenMs ?: -1}ms]")
            return VertilResult.ok(
                Output(
                    text = cumulativeText,
                    ids = generated,
                    tokensGenerated = tokens,
                    durationMs = duration,
                    firstTokenMs = firstTokenMs,
                    tokensPerSecond = tps,
                    prefillMs = prefillMs,
                    finishReason = reason
                )
            )
        } catch (oom: OutOfMemoryError) {
            return VertilResult.fail(
                "Memoria insuficiente durante la generación. Prueba un modelo más pequeño " +
                    "o reduce el máximo de tokens.",
                oom, TAG, "OOM"
            )
        } catch (t: Throwable) {
            VertilLog.e(TAG, "GENERATION_FAILED: ${t.message}", t)
            return VertilResult.fail(
                "Generación fallida: ${t.message}\n${plan.describe()}",
                t, TAG, "GENERATION_FAILED"
            )
        } finally {
            promptTensors?.values?.forEach { closeQuietly(it) }
            zeroCaches.forEach { closeQuietly(it) }
            closeQuietly(result)
        }
    }

    // ============================ TENSORES ============================

    /**
     * Tensores de secuencia para un paso:
     *  - input_ids:    [1, S]
     *  - attention_mask: [1, pastLen + S] con todos los valores 1 — contrato de
     *    los decoders causales exportados con past_key_values (HF/ORT): la
     *    máscara representa la secuencia TOTAL atendida (cache + paso actual),
     *    no solo el fragmento alimentado. Batch=1 sin padding ⇒ todos unos.
     *  - position_ids: [1, S] (pastLen..pastLen+S-1).
     * Solo incluye las entradas que el plan declara.
     */
    internal fun buildSequenceTensors(ids: List<Int>, pastLen: Int): Map<String, OnnxTensor> {
        val s = ids.size
        val shape = longArrayOf(1, s.toLong())
        val out = LinkedHashMap<String, OnnxTensor>(4)

        out[plan.tokenInput] = OnnxTensor.createTensor(
            env, java.nio.LongBuffer.wrap(ids.map { it.toLong() }.toLongArray()), shape
        )
        plan.attentionMaskInput?.let { name ->
            val totalLen = pastLen + s
            val maskShape = longArrayOf(1, totalLen.toLong())
            out[name] = OnnxTensor.createTensor(
                env, java.nio.LongBuffer.wrap(LongArray(totalLen) { 1L }), maskShape
            )
        }
        plan.positionIdsInput?.let { name ->
            out[name] = OnnxTensor.createTensor(
                env, java.nio.LongBuffer.wrap(LongArray(s) { (pastLen + it).toLong() }), shape
            )
        }
        return out
    }

    /**
     * KV cache inicial de longitud 0 (prefill). Geometría derivada del grafo
     * (dimensiones estáticas) + config.json (kv_heads, head_dim) cuando una
     * dimensión dinámica lo exige.
     */
    internal fun buildZeroLengthCaches(): Pair<List<OnnxTensor>, List<OnnxTensor>> {
        if (!plan.supportsKvCache) return emptyList<OnnxTensor>() to emptyList()
        val grow = plan.metadata.kvGrowDim
        val kvHeads = modelConfig?.numKeyValueHeads?.takeIf { it > 0 } ?: 1
        val headDim = deriveHeadDim()
        val caches = ArrayList<OnnxTensor>(plan.cacheInputs.size)
        for (spec in plan.metadata.allInputs) {
            if (spec.name !in plan.cacheInputs) continue
            val shape = spec.shape.mapIndexed { dim, d ->
                when {
                    dim == grow -> 0L
                    d > 0 -> d
                    dim == 1 && spec.rank == 4 -> kvHeads.toLong()      // kv_heads dinámico
                    dim == 3 && spec.rank == 4 -> headDim.toLong()      // head_dim dinámico
                    dim == 2 && spec.rank == 3 -> (kvHeads * headDim).toLong()
                    else -> 1L                                           // batch u otro
                }
            }.toLongArray()
            // Nota: el overload ShortBuffer de ORT crea tensores FLOAT16 por definición.
            val tensor = if (spec.elemType == OnnxJavaType.FLOAT16) {
                OnnxTensor.createTensor(env, ShortBuffer.wrap(ShortArray(0)), shape)
            } else {
                OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(FloatArray(0)), shape)
            }
            caches.add(tensor)
        }
        return caches to caches
    }

    private fun deriveHeadDim(): Long {
        val heads = modelConfig?.numAttentionHeads?.takeIf { it > 0 } ?: 1
        val hidden = modelConfig?.hiddenSize?.takeIf { it > 0 } ?: 1
        return (hidden / heads).coerceAtLeast(1).toLong()
    }

    private fun runSession(
        inputs: Map<String, OnnxTensor>,
        step: String
    ): OrtSession.Result {
        return try {
            session.run(inputs)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "ONNX Runtime rechazó la ejecución ($step): ${t.message}", t
            )
        }
    }

    // ============================ GREEDY ============================

    /**
     * argmax sobre la ÚLTIMA posición de logits (especificación §10):
     *  - rank 3 [B, S, V] → fila S-1;
     *  - rank 2 [B, V]    → fila 0 (exportaciones que solo devuelven el último paso);
     *  - FLOAT y FLOAT16 soportados.
     */
    internal fun argmaxLastPosition(result: OrtSession.Result): Int? {
        val tensor = tensorOf(result, plan.logitsOutput) ?: run {
            VertilLog.e(TAG, "Salida '${plan.logitsOutput}' no presente o no tensor")
            return null
        }
        val info = tensor.info
        val shape = info.getShape()
        val vocab = shape.lastOrNull()?.takeIf { it > 0 } ?: return null

        // offset de la última fila
        val offset: Long = when (shape.size) {
            3 -> (shape[1] - 1) * vocab
            2 -> 0L
            else -> return null
        }

        return when (info.type) {
            OnnxJavaType.FLOAT -> {
                val buf = tensor.floatBuffer ?: return null
                argmaxFloat(buf, offset, vocab)
            }
            OnnxJavaType.FLOAT16 -> {
                val buf = tensor.shortBuffer ?: return null
                argmaxFloat16(buf, offset, vocab)
            }
            else -> {
                VertilLog.e(TAG, "Tipo de logits no soportado: ${info.type}")
                null
            }
        }
    }

    private fun argmaxFloat(buf: java.nio.FloatBuffer, offset: Long, vocab: Long): Int? {
        var bestIdx = -1
        var bestVal = Float.NEGATIVE_INFINITY
        val base = offset.toInt()
        for (i in 0 until vocab.toInt()) {
            val v = buf.get(base + i)
            if (v > bestVal || bestIdx < 0) { bestVal = v; bestIdx = i }
        }
        return if (bestIdx >= 0) bestIdx else null
    }

    private fun argmaxFloat16(buf: java.nio.ShortBuffer, offset: Long, vocab: Long): Int? {
        var bestIdx = -1
        var bestVal = Float.NEGATIVE_INFINITY
        val base = offset.toInt()
        for (i in 0 until vocab.toInt()) {
            val v = Float16.toFloat(buf.get(base + i))
            if (v > bestVal || bestIdx < 0) { bestVal = v; bestIdx = i }
        }
        return if (bestIdx >= 0) bestIdx else null
    }

    /** ORT 1.18: Result.get(String) devuelve Optional<OnnxValue>. */
    private fun tensorOf(result: OrtSession.Result, name: String): OnnxTensor? =
        result.get(name).orElse(null) as? OnnxTensor

    private fun closeQuietly(value: AutoCloseable?) {
        try { value?.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "OnnxLmEngine"

        /**
         * Deriva la configuración por defecto del motor desde los archivos del
         * paquete (especificación §13): EOS desde generation_config.json →
         * config.json; maxNewTokens de generation_config o 256.
         */
        fun defaultConfig(
            generationConfig: HfConfigs.GenerationConfig?,
            modelConfig: HfConfigs.ModelConfig?,
            tokenizerEosId: Int?
        ): EngineConfig {
            val eos = buildSet {
                addAll(generationConfig?.eosTokenIds ?: emptyList())
                if (isEmpty()) addAll(modelConfig?.eosTokenIds ?: emptyList())
                tokenizerEosId?.let { add(it) }
            }
            return EngineConfig(
                eosTokenIds = eos,
                maxNewTokens = generationConfig?.maxNewTokens ?: 256,
                contextLimit = modelConfig?.maxPositionEmbeddings
            )
        }
    }
}
