package com.vertil.ui

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Contratos SAF que exponen los flags REALES del Intent devuelto
 * (especificación §5). Los contratos base de androidx exponen solo el Uri y
 * su `parseResult` es final, así que implementamos [ActivityResultContract]
 * directamente — replicando el comportamiento de OpenDocument(s) y capturando
 * los flags que indican qué persistencias pueden tomarse.
 */
sealed class DocumentPickContract {

    internal companion object {
        const val GRANT_MASK =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        internal fun extractUris(intent: Intent?): List<android.net.Uri> {
            val uris = ArrayList<android.net.Uri>()
            val clip = intent?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            if (uris.isEmpty()) intent?.data?.let { uris.add(it) }
            return uris
        }
    }

    /** Selección múltiple: .onnx junto a sus recursos auxiliares (§4/§28). */
    class OpenMultipleDocuments :
        ActivityResultContract<Array<String>, OpenMultipleDocuments.Picked?>() {

        data class Picked(val uris: List<android.net.Uri>, val flags: Int)

        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, input)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(GRANT_MASK)

        override fun parseResult(resultCode: Int, intent: Intent?): Picked? {
            if (resultCode != android.app.Activity.RESULT_OK) return null
            val uris = extractUris(intent)
            if (uris.isEmpty()) return null
            return Picked(uris, (intent?.flags ?: 0) and GRANT_MASK)
        }
    }

    /** Selección simple (un documento). */
    class OpenDocument :
        ActivityResultContract<Array<String>, OpenDocument.Picked?>() {

        data class Picked(val uri: android.net.Uri, val flags: Int)

        override fun createIntent(context: Context, input: Array<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, input)
                .addFlags(GRANT_MASK)

        override fun parseResult(resultCode: Int, intent: Intent?): Picked? {
            if (resultCode != android.app.Activity.RESULT_OK) return null
            val uri = extractUris(intent).firstOrNull() ?: return null
            return Picked(uri, (intent?.flags ?: 0) and GRANT_MASK)
        }
    }
}
