package com.vertil.tools

import com.vertil.core.VertilResult
import com.vertil.permissions.PermissionCapability
import kotlinx.serialization.Serializable

/**
 * Parámetros de entrada de una herramienta.
 */
@Serializable
data class ToolInput(
    val args: Map<String, String> = emptyMap(),
    val text: String = ""
) {
    fun arg(key: String): String? = args[key]
    fun argOrDefault(key: String, default: String): String = args[key] ?: default
    fun argInt(key: String): Int? = args[key]?.toIntOrNull()
    fun argLong(key: String): Long? = args[key]?.toLongOrNull()
    fun argBoolean(key: String, default: Boolean = false): Boolean = args[key]?.toBooleanOrNull() ?: default

    companion object { val EMPTY = ToolInput() }
}

private fun String.toBooleanOrNull(): Boolean? =
    when (lowercase()) { "true", "1", "yes", "y", "on" -> true; "false", "0", "no", "n", "off" -> false; else -> null }

/**
 * Resultado estructurado de una herramienta.
 * El modelo recibe ESTO, no strings ambiguos.
 */
@Serializable
sealed class ToolResult {
    @Serializable
    data class Success(
        val operation: String,
        val data: Map<String, String> = emptyMap(),
        val message: String? = null
    ) : ToolResult()

    @Serializable
    data class Error(
        val operation: String,
        val code: String,
        val message: String
    ) : ToolResult()
}

/**
 * Contrato común a todas las herramientas de VERTIL.
 *
 * Para añadir una herramienta nueva:
 *  1. Implementar [Tool].
 *  2. Registrarla en [ToolManager.register].
 *  El resto del sistema no necesita cambios.
 */
interface Tool {
    val id: String
    val name: String
    val description: String
    val capabilities: List<PermissionCapability>
    fun validate(input: ToolInput): Boolean
    suspend fun execute(input: ToolInput): VertilResult<ToolResult>
}
