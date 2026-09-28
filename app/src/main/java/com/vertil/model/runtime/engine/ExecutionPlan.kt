package com.vertil.model.runtime.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtSession
import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.tokenizer.HfConfigs

/**
 * Representación intermedia del grafo ONNX (especificación §8): el
 * [OnnxLmEngine] trabaja contra este plan, no contra nombres concretos.
 *
 * ```
 * ExecutionPlan
 * ├── tokenInput           (entrada de ids)
 * ├── attentionMaskInput   (opcional)
 * ├── positionIdsInput     (opcional)
 * ├── cacheInputs[]        (KV cache de entrada, ordenados y emparejados)
 * ├── logitsOutput
 * ├── cacheOutputs[]       (present.* emparejado por índice con cacheInputs)
 * └── metadata             (diagnóstico completo + geometría del cache)
 * ```
 */
data class ExecutionPlan(
    val tokenInput: String,
    val attentionMaskInput: String?,
    val positionIdsInput: String?,
    val cacheInputs: List<String>,
    val cacheOutputs: List<String>,
    val logitsOutput: String,
    val metadata: PlanMetadata
) {
    val supportsKvCache: Boolean get() = cacheInputs.isNotEmpty() && cacheInputs.size == cacheOutputs.size

    override fun toString(): String = buildString {
        append("ExecutionPlan(")
        append("tokens='").append(tokenInput).append('\'')
        attentionMaskInput?.let { append(", mask='").append(it).append('\'') }
        positionIdsInput?.let { append(", positions='").append(it).append('\'') }
        if (cacheInputs.isNotEmpty()) {
            append(", cacheIn=").append(cacheInputs.size)
            append(", cacheOut=").append(cacheOutputs.size)
        }
        append(", logits='").append(logitsOutput).append("'")
        append(")")
    }

    /** Diagnóstico técnico completo (para errores "Model incompatible"). */
    fun describe(): String = buildString {
        appendLine("Plan de ejecución:")
        appendLine("  entrada tokens:    '$tokenInput'")
        appendLine("  attention mask:    ${attentionMaskInput ?: "—"}")
        appendLine("  position ids:      ${positionIdsInput ?: "—"}")
        appendLine("  KV cache entrada:  ${cacheInputs.size} tensores")
        appendLine("  KV cache salida:   ${cacheOutputs.size} tensores")
        appendLine("  logits:            '$logitsOutput'")
        appendLine("  soporte KV cache:  ${if (supportsKvCache) "sí" else "no (re-alimentación completa)"}")
    }
}

/** Metadatos del plan: geometría del cache y descriptor de tensores. */
data class PlanMetadata(
    /** Todos los inputs/outputs declarados por el grafo. */
    val allInputs: List<TensorSlotSpec>,
    val allOutputs: List<TensorSlotSpec>,
    /**
     * Dimensión que CRECE con el contexto en los tensores de cache
     * (convención Llama-style: dim 2 de [B, kv_heads, seq, head_dim]).
     */
    val kvGrowDim: Int,
    /** true si los tensores de cache son FLOAT16 (ShortBuffer en ORT). */
    val kvIsFloat16: Boolean,
    val notes: List<String>
)

/** Descriptor de un tensor declarado por la sesión (tipo + forma reales). */
data class TensorSlotSpec(
    val name: String,
    val elemType: OnnxJavaType,
    val shape: List<Long>   // -1 = dimensión dinámica
) {
    val rank: Int get() = shape.size
    fun isDynamic(dim: Int): Boolean = dim < shape.size && shape[dim] <= 0L
    override fun toString(): String =
        "$name[type=$elemType, shape=${shape.joinToString("×") { if (it <= 0) "?" else it.toString() }}]"
}

/**
 * Planificador: inspecciona OrtSession.getInputInfo()/getOutputInfo() y
 * clasifica cada tensor SIN depender exclusivamente de nombres (especificación
 * §7). Estrategia por capas:
 *
 *  1. dtype + rango (estructura real del grafo);
 *  2. pistas de nombre (convenciones HF: input_ids / attention_mask /
 *     position_ids / past_key_values / present / logits);
 *  3. geometría vs config.json (vocab_size, num_key_value_heads, hidden…).
 *
 * Si el grafo no puede interpretarse de forma segura → Failure
 * "Modelo incompatible" con diagnóstico útil. NUNCA se ejecuta a ciegas.
 */
object ExecutionPlanner {

    private const val TAG = "ExecutionPlanner"

    /** Clasificación de entradas INT64 de rango 2 (tokens/máscara/posiciones). */
    private data class Int2Slots(
        val candidates: List<TensorSlotSpec>,
        val token: TensorSlotSpec?,
        val mask: TensorSlotSpec?,
        val positions: TensorSlotSpec?
    )

    fun plan(session: OrtSession, modelConfig: HfConfigs.ModelConfig?): VertilResult<ExecutionPlan> {
        val notes = ArrayList<String>()

        val allInputs = session.inputNames.mapNotNull { name ->
            specOf(name, session.inputInfo[name], notes)
        }
        val allOutputs = session.outputNames.mapNotNull { name ->
            specOf(name, session.outputInfo[name], notes)
        }
        if (allInputs.isEmpty()) return incompatible(
            "El grafo no declara entradas.", allInputs, allOutputs
        )

        // ================= 1. entradas INT64 rank-2 =================
        val int2 = allInputs.filter { it.elemType == OnnxJavaType.INT64 && it.rank == 2 }
        if (int2.isEmpty()) {
            return incompatible(
                "No se encontró una entrada de tokens (INT64, rango 2). " +
                    "Inputs reales: ${allInputs.joinToString { it.toString() }}. " +
                    "VERTIL soporta modelos generativos de lenguaje (causal LM); " +
                    "este grafo parece otro tipo de tarea.",
                allInputs, allOutputs
            )
        }
        val slots = classifyInt2(int2)

        // ================= 2. KV cache: entradas float rank>=3 =================
        val reserved = setOfNotNull(slots.token?.name, slots.mask?.name, slots.positions?.name)
        val cacheInputs = allInputs.filter {
            it.name !in reserved && it.elemType in FLOAT_TYPES && it.rank >= 3
        }
        // Cualquier entrada restante no reconocida se documenta (y se omite).
        val unknownInputs = allInputs.filter {
            it.name !in reserved && it !in cacheInputs &&
                it !in int2 // int64 rank≠2 (p.ej. entrada extra 1D)
        }
        unknownInputs.forEach {
            notes.add("Entrada no utilizada (se omite): ${it.name}[${it.elemType}, rank=${it.rank}]")
        }

        // ================= 3. salida logits =================
        val logits = findLogitsOutput(allOutputs, modelConfig)
            ?: return incompatible(
                "No se pudo identificar la salida de logits. Salidas reales: " +
                    "${allOutputs.joinToString { it.toString() }}." +
                    (modelConfig?.vocabSize?.let { " (vocab_size declarado: $it)" } ?: ""),
                allInputs, allOutputs
            )

        // ================= 4. salidas de cache + emparejamiento =================
        val cacheOutputs = allOutputs.filter {
            it.name != logits.name && it.elemType in FLOAT_TYPES && it.rank >= 3
        }
        val pairing = pairCache(cacheInputs, cacheOutputs)
        if (cacheInputs.isNotEmpty() && pairing == null) {
            // Cache declarado pero sin salida emparejable: ejecutar SIN cache
            // sería O(n²) e impreciso para algunos grafos → mejor error claro.
            return incompatible(
                "El grafo declara ${cacheInputs.size} entradas de KV cache pero no se " +
                    "pudieron emparejar con salidas de cache (${cacheOutputs.size}). " +
                    "Entradas: ${cacheInputs.joinToString { it.name }} · " +
                    "Salidas: ${cacheOutputs.joinToString { it.name }}",
                allInputs, allOutputs
            )
        }
        val (cacheIn, cacheOut) = pairing ?: (emptyList<String>() to emptyList<String>())
        if (cacheInputs.isEmpty()) {
            notes.add("Grafo sin KV cache: el engine re-alimentará la secuencia completa en cada paso.")
        }

        // ================= 5. geometría del cache =================
        val growDim = deriveGrowDim(cacheInputs, notes)
        val kvIsF16 = cacheIn.isNotEmpty() &&
            specOfName(allInputs, cacheIn.first())?.elemType == OnnxJavaType.FLOAT16

        val plan = ExecutionPlan(
            tokenInput = slots.token!!.name,
            attentionMaskInput = slots.mask?.name,
            positionIdsInput = slots.positions?.name,
            cacheInputs = cacheIn,
            cacheOutputs = cacheOut,
            logitsOutput = logits.name,
            metadata = PlanMetadata(
                allInputs = allInputs,
                allOutputs = allOutputs,
                kvGrowDim = growDim,
                kvIsFloat16 = kvIsF16,
                notes = notes
            )
        )
        notes.forEach { VertilLog.i(TAG, it) }
        VertilLog.i(TAG, plan.toString())
        return VertilResult.ok(plan)
    }

    // ============================ clasificación ============================

    private fun classifyInt2(int2: List<TensorSlotSpec>): Int2Slots {
        fun has(name: TensorSlotSpec, kw: String) = name.name.lowercase().contains(kw)
        val token = int2.firstOrNull { has(it, "token") || has(it, "input") }
            ?: int2.firstOrNull { !has(it, "mask") && !has(it, "position") }
            ?: int2.first()
        val mask = int2.firstOrNull { it != token && has(it, "mask") }
            // convención HF: el segundo INT64 rank-2 suele ser la máscara
            ?: int2.firstOrNull { it != token && it.name == "attention_mask" }
            ?: int2.firstOrNull { it != token && !has(it, "position") }
        val positions = int2.firstOrNull { it != token && it != mask && has(it, "position") }
            ?: int2.firstOrNull { it != token && it != mask && has(it, "pos") }
        return Int2Slots(int2, token, mask, positions)
    }

    private fun findLogitsOutput(
        outputs: List<TensorSlotSpec>,
        modelConfig: HfConfigs.ModelConfig?
    ): TensorSlotSpec? {
        val float = outputs.filter { it.elemType in FLOAT_TYPES }
        // 1) nombre explícito
        float.firstOrNull { it.name.lowercase().contains("logit") }?.let { return it }
        // 2) última dimensión == vocab_size (geometría real, no nombre)
        val vocab = modelConfig?.vocabSize
        if (vocab != null && vocab > 0) {
            float.firstOrNull { it.shape.isNotEmpty() && it.shape.last() == vocab.toLong() }
                ?.let { return it }
        }
        // 3) fallback: salida float rank-3 con la mayor última dimensión
        return float.filter { it.rank == 3 }.maxByOrNull { it.shape.lastOrNull() ?: 0L }
    }

    /**
     * Empareja entradas de cache con salidas de cache:
     *  a) por sufijo de nombre: `past_key_values.N.key` ↔ `present.N.key`;
     *  b) si no, por orden ordinal (misma cantidad, rango compatible).
     * Devuelve null si hay entradas de cache pero el emparejamiento es imposible.
     */
    private fun pairCache(
        inputs: List<TensorSlotSpec>,
        outputs: List<TensorSlotSpec>
    ): Pair<List<String>, List<String>>? {
        if (inputs.isEmpty()) return emptyList<String>() to emptyList()
        if (outputs.isEmpty()) return null

        val suffix = Regex("(\\d+)\\.(key|value)$")
        val outBySuffix = HashMap<String, TensorSlotSpec>()
        outputs.forEach { o ->
            suffix.find(o.name)?.let { m -> outBySuffix["${m.groupValues[1]}.${m.groupValues[2]}"] = o }
        }
        val pairedIn = ArrayList<String>(inputs.size)
        val pairedOut = ArrayList<String>(inputs.size)
        val usedOuts = HashSet<String>()

        // a) por nombre
        var allNamed = true
        for (i in inputs.indices) {
            val m = suffix.find(inputs[i].name)
            val match = m?.let { outBySuffix["${it.groupValues[1]}.${it.groupValues[2]}"] }
                ?.takeIf { it.name !in usedOuts }
            if (match != null) {
                pairedIn.add(inputs[i].name); pairedOut.add(match.name); usedOuts.add(match.name)
            } else allNamed = false
        }
        if (allNamed) return pairedIn to pairedOut

        // b) por orden (mismas cantidades y rangos compatibles)
        if (inputs.size == outputs.size && inputs.indices.all { inputs[it].rank == outputs[it].rank }) {
            return inputs.map { it.name } to outputs.map { it.name }
        }
        return null
    }

    /**
     * Dimensión que crece con el contexto: la única dinámica si existe una;
     * si no, convención (dim 2 para rank-4, dim 1 para rank-3).
     */
    private fun deriveGrowDim(cacheInputs: List<TensorSlotSpec>, notes: MutableList<String>): Int {
        val first = cacheInputs.firstOrNull() ?: return 2
        val dynIdx = first.shape.withIndex().filter { it.value <= 0L }.map { it.index }
        return when {
            dynIdx.size == 1 -> dynIdx[0]
            first.rank == 4 -> 2
            first.rank == 3 -> 1
            else -> first.rank - 2
        }.also {
            if (dynIdx.size > 1) notes.add(
                "Cache '${first.name}' tiene ${dynIdx.size} dimensiones dinámicas; " +
                    "se asume dim $it como eje de secuencia."
            )
        }
    }

    // ============================ utilidades ============================

    private val FLOAT_TYPES = setOf(OnnxJavaType.FLOAT, OnnxJavaType.FLOAT16)

    private fun specOf(name: String, nodeInfo: NodeInfo?, notes: MutableList<String>): TensorSlotSpec? {
        val info: ai.onnxruntime.TensorInfo? = try {
            nodeInfo?.getInfo() as? ai.onnxruntime.TensorInfo
        } catch (t: Throwable) {
            notes.add("No se pudo inspeccionar '$name': ${t.message}")
            null
        }
        return if (info != null) {
            TensorSlotSpec(name, info.type, info.getShape().map { it })
        } else {
            notes.add("Entrada/salida '$name' no es tensor (se ignora).")
            null
        }
    }

    private fun specOfName(all: List<TensorSlotSpec>, name: String): TensorSlotSpec? =
        all.firstOrNull { it.name == name }

    private fun incompatible(
        msg: String,
        inputs: List<TensorSlotSpec>,
        outputs: List<TensorSlotSpec>
    ): VertilResult<ExecutionPlan> {
        val diag = buildString {
            appendLine(msg)
            appendLine("Entradas del grafo:")
            inputs.forEach { appendLine("  · $it") }
            appendLine("Salidas del grafo:")
            outputs.forEach { appendLine("  · $it") }
        }
        VertilLog.e(TAG, diag)
        return VertilResult.fail(
            "Modelo incompatible: $msg",
            module = TAG, code = "MODEL_INCOMPATIBLE"
        )
    }
}
