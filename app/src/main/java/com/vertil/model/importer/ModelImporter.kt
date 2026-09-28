package com.vertil.model.importer

import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.model.ModelFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Copia streaming Uri → almacenamiento privado de VERTIL (especificación §6):
 *  - SIN copia intermedia .bin: InputStream → models/.importing_* → models/<nombre>;
 *  - SHA-256 calculado EN VUELO (sin releer GBs — §9);
 *  - verificación de tamaño contra los metadatos del proveedor (anti parcial — §6);
 *  - validación de contenido ANTES de exponer el archivo (§8);
 *  - rename atómico dentro del mismo directorio;
 *  - limpieza garantizada del temporal en error/cancelación;
 *  - colisiones resueltas deterministamente sin sobrescribir (§7).
 *
 * No depende de Android (solo java.io): testeable en JVM.
 */
class ModelImporter(
    private val modelsDir: File,
    private val deepValidator: ModelFileValidator.DeepValidator? = null
) {

    /** Hook opcional de progreso por etapas (para UI/logs). */
    var onStage: (suspend (ModelImportStage) -> Unit)? = null

    data class ImportedFile(
        val file: File,
        val sha256: String,
        val sizeBytes: Long,
        val displayName: String,
        val format: ModelFormat
    )

    /**
     * Copia [openStream]() al almacenamiento privado y devuelve el archivo final.
     *
     * @param displayName nombre REAL del documento (ya resuelto por la capa superior).
     * @param format formato detectado con el nombre real (nunca con el temporal).
     * @param expectedSize tamaño reportado por el proveedor o null si desconocido.
     * @param openStream proveedor del InputStream (reabrible en reintentos).
     * @param packageDir directorio destino dentro de modelsDir (paquete multi-archivo
     *   — especificación §4); null = modelsDir raíz (archivo suelto, Fase 1).
     */
    suspend fun import(
        displayName: String,
        format: ModelFormat,
        expectedSize: Long?,
        openStream: () -> InputStream,
        packageDir: File? = null
    ): VertilResult<ImportedFile> {
        if (format == ModelFormat.UNKNOWN) {
            return VertilResult.fail(
                "Formato no soportado: '$displayName'. VERTIL soporta .onnx, .gguf y .tflite.",
                module = "ModelImporter", code = "UNSUPPORTED_FORMAT"
            )
        }
        return importInternal(displayName, expectedSize, openStream, packageDir, validateModel = true, format = format)
    }

    /**
     * Importación de archivos AUXILIARES del paquete (tokenizer.json,
     * config.json…): streaming + SHA-256 idénticos, pero sin validación de
     * firma de modelos. Los .json deben parsear correctamente.
     */
    suspend fun importAux(
        displayName: String,
        expectedSize: Long?,
        openStream: () -> InputStream,
        packageDir: File
    ): VertilResult<ImportedFile> {
        if (displayName.lowercase().endsWith(".json")) {
            // Validación de contenido: un tokenizer.json corrupto no debe entrar al paquete.
            return try {
                openStream().use { input ->
                    val text = input.readBytes().decodeToString()
                    kotlinx.serialization.json.Json.parseToJsonElement(text)
                }
                importStreamVerified(displayName, expectedSize, openStream, packageDir)
            } catch (t: Throwable) {
                VertilResult.fail(
                    "El archivo auxiliar '$displayName' no es JSON válido: ${t.message}",
                    t, "ModelImporter", "AUX_INVALID_JSON"
                )
            }
        }
        return importStreamVerified(displayName, expectedSize, openStream, packageDir)
    }

    private suspend fun importStreamVerified(
        displayName: String,
        expectedSize: Long?,
        openStream: () -> InputStream,
        packageDir: File?
    ): VertilResult<ImportedFile> = importInternal(
        displayName, expectedSize, openStream, packageDir,
        validateModel = false, format = ModelFormat.UNKNOWN
    )

    private suspend fun importInternal(
        displayName: String,
        expectedSize: Long?,
        openStream: () -> InputStream,
        packageDir: File?,
        validateModel: Boolean,
        format: ModelFormat
    ): VertilResult<ImportedFile> {
        val baseDir = packageDir ?: modelsDir
        if (!baseDir.exists()) baseDir.mkdirs()
        val finalName = ModelFileNameSanitizer.resolveCollision(
            baseDir, ModelFileNameSanitizer.sanitize(displayName)
        )
        val tempFile = File(baseDir, ModelFileNameSanitizer.importingName(finalName))

        try {
            // 1. Espacio disponible (margen 5%) cuando el tamaño es conocido. Overflow-safe.
            if (expectedSize != null && expectedSize > 0) {
                val usable = baseDir.usableSpace
                val usableWithMargin = usable - usable / 20
                if (expectedSize > usableWithMargin) {
                    return VertilResult.fail(
                        "Espacio insuficiente: se necesitan ${formatBytes(expectedSize)} y hay ${formatBytes(usable)} disponibles.",
                        module = "ModelImporter", code = "INSUFFICIENT_SPACE"
                    )
                }
            }

            // 2. Copia streaming + SHA-256 en vuelo.
            onStage?.invoke(ModelImportStage.COPY)
            val digest = MessageDigest.getInstance("SHA-256")
            var copied: Long = 0
            val buffer = ByteArray(COPY_BUFFER_BYTES)

            try {
                openStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive() // cancelable sin dejar basura
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            } catch (e: IOException) {
                // ENOSPC u otra E/S durante la copia.
                VertilLog.e(
                    "ModelImporter",
                    "IMPORT_MODEL_FAILED stage=COPY filename=$finalName reason=${e.message}", e
                )
                return VertilResult.fail(
                    "Error copiando el modelo: ${e.message ?: "error de E/S"}.",
                    e, "ModelImporter", "COPY_FAILED"
                )
            }

            // 3. Verificación anti parcial: tamaño copiado == tamaño reportado.
            if (expectedSize != null && copied != expectedSize) {
                return VertilResult.fail(
                    "Copia incompleta: se copiaron $copied de $expectedSize bytes.",
                    module = "ModelImporter", code = "SIZE_MISMATCH"
                )
            }
            if (copied == 0L) {
                return VertilResult.fail(
                    "El documento seleccionado está vacío.",
                    module = "ModelImporter", code = "EMPTY_FILE"
                )
            }

            // 4. Validación del contenido (básica + profunda si está disponible).
            if (validateModel) {
                onStage?.invoke(ModelImportStage.VALIDATE)
                val validation = ModelFileValidator.validate(tempFile, format, copied, deepValidator)
                if (validation is VertilResult.Failure) {
                    return VertilResult.fail(
                        validation.message, validation.cause, "ModelImporter", validation.code
                    )
                }
            }

            // 5. Rename atómico (mismo directorio).
            val finalFile = File(baseDir, finalName)
            if (!tempFile.renameTo(finalFile)) {
                return VertilResult.fail(
                    "No se pudo finalizar la importación (rename).",
                    module = "ModelImporter", code = "RENAME_FAILED"
                )
            }

            onStage?.invoke(ModelImportStage.REGISTER)
            return VertilResult.ok(
                ImportedFile(
                    file = finalFile,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    sizeBytes = copied,
                    displayName = finalName,
                    format = format
                )
            )
        } catch (ce: CancellationException) {
            VertilLog.i("ModelImporter", "Importación cancelada: $finalName")
            throw ce
        } finally {
            // Garantía anti-parcial (§6): si el temporal sigue aquí, algo falló → borrar.
            if (tempFile.exists()) {
                val deleted = tempFile.delete()
                VertilLog.w(
                    "ModelImporter",
                    "Temporal eliminado tras importación no completada: ${tempFile.name.take(24)}… (borrado=$deleted)"
                )
            }
        }
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 256 * 1024

        /** Limpia temporales de importaciones interrumpidas (app cerrada a mitad). */
        fun cleanupStaleTemporaries(modelsDir: File) {
            val stale = modelsDir.listFiles { f ->
                (f.name.startsWith(".importing_") || f.isDirectory && f.name.startsWith(".importing_"))
            } ?: return
            stale.forEach {
                VertilLog.w("ModelImporter", "Eliminando temporal de importación interrumpida: ${it.name.take(24)}…")
                it.delete()
            }
            // temporales dentro de paquetes
            modelsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
                dir.listFiles { f -> f.name.startsWith(".importing_") }?.forEach {
                    VertilLog.w("ModelImporter", "Eliminando temporal en paquete ${dir.name}: ${it.name.take(24)}…")
                    it.delete()
                }
            }
        }

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}

/** Etapas de una importación (para logs y UI). */
enum class ModelImportStage {
    /** Consulta de metadatos del documento (nombre/tamaño). */
    METADATA,
    /** Copia streaming con SHA-256 en vuelo. */
    COPY,
    /** Validación del contenido (firma / ONNX Runtime). */
    VALIDATE,
    /** Registro en Room. */
    REGISTER
}
