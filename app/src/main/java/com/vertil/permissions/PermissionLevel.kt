package com.vertil.permissions

import kotlinx.serialization.Serializable

/**
 * Niveles de permiso de VERTIL.
 *
 * 0 — Lectura: consultar archivos/metadatos/contenido autorizado.
 * 1 — Organización: crear carpetas, mover/copiar/renombrar archivos.
 * 2 — Modificación: transformar archivos, comprimir, modificar contenido.
 * 3 — Automatización: ejecutar reglas, procesar archivos automáticamente.
 * 4 — Acción sensible: SIEMPRE requiere confirmación explícita del usuario.
 */
@Serializable
enum class PermissionLevel(val priority: Int, val label: String, val requiresExplicitConfirm: Boolean) {
    READ(0, "Lectura", false),
    ORGANIZE(1, "Organización", false),
    MODIFY(2, "Modificación", false),
    AUTOMATE(3, "Automatización", false),
    SENSITIVE(4, "Acción sensible", true);

    fun isAtLeast(other: PermissionLevel): Boolean = priority >= other.priority
    companion object { fun fromInt(p: Int): PermissionLevel? = values().firstOrNull { it.priority == p } }
}

/**
 * Capacidades individuales que una herramienta puede solicitar.
 * Cada capacidad se mapea a un [PermissionLevel].
 */
@Serializable
enum class PermissionCapability(val level: PermissionLevel, val label: String) {
    READ_FILE_METADATA(PermissionLevel.READ, "Leer metadatos de archivos"),
    READ_FILE_CONTENT(PermissionLevel.READ, "Leer contenido de archivos"),
    LIST_DIRECTORIES(PermissionLevel.READ, "Listar directorios"),
    SEARCH_FILES(PermissionLevel.READ, "Buscar archivos"),
    CREATE_FOLDER(PermissionLevel.ORGANIZE, "Crear carpetas"),
    MOVE_FILE(PermissionLevel.ORGANIZE, "Mover archivos"),
    COPY_FILE(PermissionLevel.ORGANIZE, "Copiar archivos"),
    RENAME_FILE(PermissionLevel.ORGANIZE, "Renombrar archivos"),
    COMPRESS_FILE(PermissionLevel.MODIFY, "Comprimir archivos"),
    EXTRACT_ARCHIVE(PermissionLevel.MODIFY, "Descomprimir archivos"),
    TRANSFORM_FILE(PermissionLevel.MODIFY, "Transformar contenido de archivos"),
    DELETE_FILE(PermissionLevel.SENSITIVE, "Eliminar archivos"),
    RUN_AUTOMATION(PermissionLevel.AUTOMATE, "Ejecutar automatizaciones"),
    ACCESS_DEVICE_INFO(PermissionLevel.READ, "Acceder a información del dispositivo"),
    ACCESS_MODEL_INFO(PermissionLevel.READ, "Acceder a información del modelo")
}

/**
 * Resultado de una solicitud de permiso.
 */
sealed class PermissionDecision {
    object Granted : PermissionDecision()
    data class NeedsConfirmation(val capabilities: List<PermissionCapability>) : PermissionDecision()
    data class Denied(val reason: String, val capabilities: List<PermissionCapability>) : PermissionDecision()
}

/**
 * Política de permisos.
 * Define qué se permite por defecto y qué requiere confirmación.
 */
data class PermissionPolicy(
    val autoConfirmLevels: Set<PermissionLevel> = setOf(PermissionLevel.READ),
    val disabledCapabilities: Set<PermissionCapability> = emptySet()
) {
    companion object {
        val DEFAULT = PermissionPolicy(
            autoConfirmLevels = setOf(PermissionLevel.READ)
        )
    }
}
