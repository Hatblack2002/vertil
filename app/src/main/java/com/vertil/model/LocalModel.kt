package com.vertil.model

import com.vertil.core.VertilResult

/**
 * Parámetros de generación.
 */
data class GenerationParams(
    val prompt: String,
    val systemPrompt: String = "",
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val stopSequences: List<String> = emptyList()
)

/**
 * Resultado de una generación.
 */
data class GenerationResult(
    val text: String,
    val tokensGenerated: Int = 0,
    val durationMs: Long = 0L,
    val tokensPerSecond: Float? = null
) {
    val speedLabel: String?
        get() = if (tokensPerSecond != null && tokensPerSecond > 0)
            "%.1f tok/s".format(tokensPerSecond) else null
}

/**
 * Contrato común que TODO runtime de modelo local debe implementar.
 *
 * El resto de la aplicación depende solo de esta interfaz, no de un runtime concreto.
 * Para añadir un runtime nuevo (GGUF, TFLite, etc.), basta crear una implementación
 * y registrarla en [ModelRuntimeRegistry].
 */
interface LocalModel {

    /** Identificador del runtime (ej: "onnx", "gguf", "tflite", "mock"). */
    val runtimeId: String

    /** Información del modelo cargado o null si no hay. */
    val info: ModelInfo?

    /** Estado actual del runtime. */
    val state: ModelState

    /** Capacidades soportadas por este runtime/modelo. */
    val capabilities: ModelCapabilities

    /**
     * Carga el modelo en memoria.
     * @return VertilResult con el [ModelInfo] cargado o Failure.
     */
    suspend fun load(info: ModelInfo): VertilResult<ModelInfo>

    /** Descarga el modelo de memoria y libera recursos. */
    suspend fun unload(): VertilResult<Unit>

    /** Genera texto a partir de [params]. */
    suspend fun generate(params: GenerationParams): VertilResult<GenerationResult>

    /** Cancela la generación en curso (si la hay). */
    fun cancel()

    /** Información en tiempo de ejecución para la sección avanzada. */
    fun getRuntimeInfo(): Map<String, String>
}
