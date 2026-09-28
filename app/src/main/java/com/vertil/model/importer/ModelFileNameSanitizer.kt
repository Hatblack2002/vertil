package com.vertil.model.importer

import java.io.File

/**
 * Sanitización de nombres de archivo provenientes de SAF (especificación §7).
 *
 * Defensas:
 *  - path traversal: se elimina cualquier componente de ruta ('/', '\\', '..');
 *  - caracteres ilegales/control: sustituidos por '_';
 *  - nombres vacíos o reservados: fallback seguro;
 *  - longitud limitada conservando la extensión;
 *  - colisiones resueltas de forma determinista con sufijo _1, _2… SIN sobrescribir.
 *
 * No depende de Android: testeable en JVM.
 */
object ModelFileNameSanitizer {

    private const val MAX_BASE_LENGTH = 120
    private val ILLEGAL = Regex("[^A-Za-z0-9._()\\- ]")
    private val CONTROL = Regex("[\\x00-\\x1F\\x7F]")

    /** Devuelve un nombre de archivo seguro a partir del nombre real del documento. */
    fun sanitize(rawName: String): String {
        // 1. Quitar cualquier componente de ruta (Unix y Windows) y traversal.
        var name = rawName.substringAfterLast('/').substringAfterLast('\\')
        name = name.replace("..", "_")
        // 2. Caracteres de control yuxtapuestos e ilegales.
        name = CONTROL.replace(name, "")
        name = ILLEGAL.replace(name.trim(), "_")
        name = name.replace(Regex("_+"), "_").trim().trim('_', ' ')
        if (name.isEmpty()) name = "modelo_importado"
        // 3. Limitar longitud conservando la extensión.
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val trimmedBase = base.take(MAX_BASE_LENGTH).trimEnd('.', ' ')
        val result = (if (trimmedBase.isEmpty()) "modelo_importado" else trimmedBase) + ext
        return result
    }

    /**
     * Resuelve colisiones en [dir] sin sobrescribir: nombre.onnx → nombre_1.onnx,
     * nombre_2.onnx… Determinista (especificación §7).
     */
    fun resolveCollision(dir: File, desiredName: String): String {
        val candidate = File(dir, desiredName)
        if (!candidate.exists()) return desiredName
        val dot = desiredName.lastIndexOf('.')
        val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val ext = if (dot > 0) desiredName.substring(dot) else ""
        var i = 1
        while (true) {
            val next = "$base" + "_$i" + ext
            if (!File(dir, next).exists()) return next
            i++
        }
    }

    /** Nombre del temporal durante la copia (prefijo punto → nunca se registra ni lista como modelo). */
    fun importingName(finalName: String): String =
        ".importing_${System.nanoTime().toString(36)}_$finalName"

    /** true si el archivo es un temporal de importación en curso. */
    fun isImportingTemp(file: File): Boolean = file.name.startsWith(".importing_")
}
