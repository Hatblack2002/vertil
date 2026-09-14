package com.vertil.model

import kotlinx.serialization.Serializable

/**
 * Estado posible de un modelo local.
 */
@Serializable
enum class ModelState {
    /** Importado pero no cargado en memoria. */
    INSTALLED,
    /** En proceso de carga. */
    LOADING,
    /** Cargado en memoria y listo para inferencia. */
    READY,
    /** En proceso de generación. */
    GENERATING,
    /** Error durante la última operación. */
    ERROR,
    /** En proceso de descarga de memoria. */
    UNLOADING
}

/**
 * Capacidades que un runtime puede ofrecer.
 */
@Serializable
data class ModelCapabilities(
    val supportsChat: Boolean = true,
    val supportsEmbeddings: Boolean = false,
    val supportsToolCalling: Boolean = false,
    val supportsStreaming: Boolean = false,
    val maxContextLength: Int = 2048,
    val maxOutputTokens: Int = 1024
)

/**
 * Información descriptiva de un modelo.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val filePath: String,
    val format: ModelFormat,
    val sizeBytes: Long,
    val quantization: String? = null,
    val contextLength: Int? = null,
    val hashSha256: String? = null,
    val importedAt: Long,
    val isActive: Boolean = false,
    val state: ModelState = ModelState.INSTALLED,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val lastErrorMessage: String? = null
) {
    val sizeHuman: String get() = humanSize(sizeBytes)
    val isReady: Boolean get() = state == ModelState.READY
    val isInstalled: Boolean get() = state != ModelState.ERROR || filePath.isNotEmpty()

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }

        val UNKNOWN: ModelInfo = ModelInfo(
            id = "none", name = "Sin modelo", filePath = "",
            format = ModelFormat.UNKNOWN, sizeBytes = 0L, importedAt = 0L,
            state = ModelState.INSTALLED
        )
    }
}

@Serializable
enum class ModelFormat(val label: String, val runtimeId: String) {
    ONNX("ONNX", "onnx"),
    GGUF("GGUF", "gguf"),
    TFLITE("TFLite", "tflite"),
    UNKNOWN("Desconocido", "unknown");

    companion object {
        fun fromExtension(filename: String): ModelFormat {
            val lower = filename.lowercase()
            return when {
                lower.endsWith(".onnx") -> ONNX
                lower.endsWith(".gguf") -> GGUF
                lower.endsWith(".tflite") || lower.endsWith(".lite") -> TFLITE
                else -> UNKNOWN
            }
        }
    }
}
