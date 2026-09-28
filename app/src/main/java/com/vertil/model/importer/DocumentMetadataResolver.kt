package com.vertil.model.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolución de metadatos REALES del documento SAF (especificación §4):
 * nombre real vía OpenableColumns.DISPLAY_NAME y tamaño vía OpenableColumns.SIZE,
 * consultados con ContentResolver.query(). PROHIBIDO derivar el nombre de
 * uri.lastPathSegment (en Downloads devuelve "msd:123" o rutas raw).
 */
interface DocumentMetadataResolver {
    suspend fun resolve(uri: Uri): VertilResult<ResolvedMetadata>
}

data class ResolvedMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?
)

class ContentResolverDocumentMetadataResolver(
    private val context: Context
) : DocumentMetadataResolver {

    override suspend fun resolve(uri: Uri): VertilResult<ResolvedMetadata> =
        withContext(Dispatchers.IO) {
            var displayName: String? = null
            var sizeBytes: Long? = null
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx)
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            val s = cursor.getLong(sizeIdx)
                            if (s >= 0) sizeBytes = s
                        }
                    }
                } ?: return@withContext VertilResult.fail(
                    "El proveedor de documentos no respondió a la consulta de metadatos.",
                    module = "MetadataResolver", code = "PROVIDER_NULL_CURSOR"
                )
            } catch (e: SecurityException) {
                return@withContext VertilResult.fail(
                    "Sin permiso para leer el documento seleccionado.",
                    e, "MetadataResolver", "SECURITY_EXCEPTION"
                )
            } catch (e: IllegalArgumentException) {
                return@withContext VertilResult.fail(
                    "El documento seleccionado no es válido.",
                    e, "MetadataResolver", "ILLEGAL_ARGUMENT"
                )
            } catch (e: android.database.sqlite.SQLiteException) {
                return@withContext VertilResult.fail(
                    "El proveedor de documentos devolvió un error.",
                    e, "MetadataResolver", "PROVIDER_ERROR"
                )
            }

            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            if (displayName != null) VertilLog.d(
                "MetadataResolver", "Documento resuelto: ${displayName!!.take(24)}… size=$sizeBytes mime=$mime"
            )
            VertilResult.ok(ResolvedMetadata(displayName, sizeBytes, mime))
        }
}
