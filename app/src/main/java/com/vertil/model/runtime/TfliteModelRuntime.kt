package com.vertil.model.runtime

import com.vertil.core.VertilResult
import com.vertil.model.GenerationParams
import com.vertil.model.GenerationResult
import com.vertil.model.LocalModel
import com.vertil.model.ModelCapabilities
import com.vertil.model.ModelInfo
import com.vertil.model.ModelState

/**
 * Runtime TFLite.
 *
 * Estado: NO IMPLEMENTADO (stub documentado).
 *
 * TFLite está disponible en Android pero la integración con modelos LLM tipo
 * Gemma/Llama requiere el interpreter + GPU delegate + tokenizador específico.
 * Se pospone a v1.1.
 */
class TfliteModelRuntime : LocalModel {

    override val runtimeId: String = "tflite"
    override val info: ModelInfo? = null
    override val state: ModelState = ModelState.INSTALLED
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override suspend fun load(info: ModelInfo): VertilResult<ModelInfo> =
        VertilResult.fail(
            message = "Runtime TFLite no implementado en v1.0.",
            module = "TfliteRuntime",
            code = "NOT_IMPLEMENTED"
        )

    override suspend fun unload(): VertilResult<Unit> = VertilResult.ok(Unit)
    override suspend fun generate(params: GenerationParams): VertilResult<GenerationResult> =
        VertilResult.fail("TFLite no implementado", module = "TfliteRuntime", code = "NOT_IMPLEMENTED")

    override fun cancel() = Unit
    override fun getRuntimeInfo(): Map<String, String> = mapOf(
        "Runtime" to "TFLite (NO IMPLEMENTADO)",
        "Estado" to "Pendiente v1.1"
    )
}
