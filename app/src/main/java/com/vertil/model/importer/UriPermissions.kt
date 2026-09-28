package com.vertil.model.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vertil.core.log.VertilLog

/**
 * Permisos persistibles de SAF (especificación §5).
 *
 * Reglas:
 *  - SOLO se piden los flags que el Intent devuelto realmente contiene;
 *  - la llamada está protegida: un SecurityException (proveedores como
 *    Downloads/USB a veces no permiten persistencia) NO crash y NO aborta la
 *    importación — el permiso de lectura sigue vigente para esta sesión;
 *  - nunca se usa como base uri.lastPathSegment para nada crítico.
 */
object UriPermissions {

    private const val GRANT_MASK =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Intenta tomar persistencia con los flags reales del Intent.
     * @return true si se tomó el permiso persistente; false si el proveedor lo
     *         rechazó (la importación continúa igualmente).
     */
    fun tryTakePersistable(context: Context, uri: Uri, intentFlags: Int): Boolean {
        val takeFlags = intentFlags and GRANT_MASK
        if (takeFlags == 0) {
            VertilLog.w("UriPermissions", "Sin flags persistibles en el Intent para ${redact(uri)}")
            return false
        }
        return try {
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            VertilLog.i("UriPermissions", "Permiso persistente tomado para ${redact(uri)} (flags=$takeFlags)")
            true
        } catch (e: SecurityException) {
            // Documentado en §5.4: NO crash, NO aborta la importación.
            VertilLog.w("UriPermissions", "El proveedor no permite persistencia (no fatal): ${e.message}")
            false
        }
    }

    /**
     * Nombre de respaldo SOLO si lastPathSegment parece un nombre de archivo
     * real (sin '/', sin ':', con extensión conocida). Nunca se usa si hay
     * DISPLAY_NAME disponible.
     */
    fun fallbackDisplayName(uri: Uri, mimeType: String?): String? {
        val last = uri.lastPathSegment ?: return null
        if (last.contains('/') || last.contains(':')) return null
        val lower = last.lowercase()
        val looksLikeFile = lower.endsWith(".onnx") || lower.endsWith(".gguf") ||
            lower.endsWith(".tflite") || lower.endsWith(".lite")
        return if (looksLikeFile) last else null
    }

    /** Nombre generado de último recurso (sin extensión → el import lo rechazará con mensaje claro). */
    fun generatedName(): String = "documento_${System.currentTimeMillis()}"

    /** Uri recortada y sin query para logs (no filtra rutas ni ids completos). */
    fun redact(uri: Uri): String = try {
        "content://${uri.authority ?: "?"}/…${uri.hashCode().toUInt()}"
    } catch (_: Exception) {
        "content://<invalid>"
    }
}
