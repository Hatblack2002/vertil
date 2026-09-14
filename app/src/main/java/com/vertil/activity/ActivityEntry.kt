package com.vertil.activity

import kotlinx.serialization.Serializable

/**
 * Entrada del registro de actividad.
 * Toda acción importante ejecutada mediante herramientas genera una entrada.
 */
@Serializable
data class ActivityEntry(
    val id: Long = 0L,
    val timestamp: Long,
    val toolId: String,
    val operation: String,
    val success: Boolean,
    val message: String,
    val source: String? = null,
    val destination: String? = null,
    val errorCode: String? = null
) {
    val icon: String get() = when (toolId) {
        "file_move" -> "↗"
        "file_copy" -> "⧉"
        "file_rename" -> "✎"
        "folder_create" -> "📁"
        "file_hash" -> "#"
        "file_search" -> "🔍"
        "file_list" -> "☰"
        "device_info" -> "ℹ"
        "model_info" -> "★"
        "task_list" -> "⏱"
        else -> "•"
    }
}
