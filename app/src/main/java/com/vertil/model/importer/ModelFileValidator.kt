package com.vertil.model.importer

import com.vertil.core.VertilResult
import com.vertil.model.ModelFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Validación de contenido real (especificación §8). La extensión NO prueba nada:
 * un .jpg renombrado a .onnx debe ser rechazado aquí.
 *
 * Nivel 1 (firmas): lee los primeros bytes y verifica la firma conocida del formato.
 * Nivel 2 (profundo, opcional): [DeepValidator] inyectable — para ONNX es ONNX Runtime
 * abriendo e inspeccionando el modelo real.
 */
object ModelFileValidator {

    /** Devuelve null si el archivo es válido para [format]; si no, mensaje de error. */
    fun interface DeepValidator {
        fun validate(file: File, format: ModelFormat): String?
    }

    fun validate(
        file: File,
        format: ModelFormat,
        sizeBytes: Long,
        deep: DeepValidator? = null
    ): VertilResult<Unit> {
        if (!file.exists() || !file.isFile) {
            return VertilResult.fail("El archivo no existe.", module = "ModelFileValidator", code = "FILE_NOT_FOUND")
        }
        if (sizeBytes <= 0 || file.length() == 0L) {
            return VertilResult.fail("El archivo está vacío.", module = "ModelFileValidator", code = "EMPTY_FILE")
        }
        if (file.length() != sizeBytes) {
            return VertilResult.fail(
                "Tamaño inconsistente: ${file.length()} bytes en disco vs $sizeBytes reportados.",
                module = "ModelFileValidator", code = "SIZE_MISMATCH"
            )
        }
        val signatureError = checkSignature(file, format)
        if (signatureError != null) return VertilResult.fail(
            signatureError, module = "ModelFileValidator", code = "INVALID_CONTENT"
        )
        if (deep != null) {
            val deepError = try {
                deep.validate(file, format)
            } catch (e: IOException) {
                "Error de E/S validando el contenido: ${e.message}"
            } catch (oom: OutOfMemoryError) {
                "Memoria insuficiente validando el contenido."
            }
            if (deepError != null) return VertilResult.fail(
                deepError, module = "ModelFileValidator", code = "INVALID_CONTENT"
            )
        }
        return VertilResult.ok(Unit)
    }

    /** Firma por formato. Devuelve null si coincide; mensaje si no. */
    fun checkSignature(file: File, format: ModelFormat): String? = try {
        when (format) {
            ModelFormat.ONNX -> {
                // protobuf file.FileProto: primer campo = field 1 (ir_version), wiretype 0 → byte 0x08.
                val head = readHead(file, 4)
                if (head.isEmpty() || head[0] != 0x08.toByte()) {
                    "El archivo no tiene la firma de un modelo ONNX válido (byte inicial 0x08)."
                } else null
            }
            ModelFormat.GGUF -> {
                val head = readHead(file, 4)
                if (head.size < 4 || head[0] != 'G'.code.toByte() || head[1] != 'G'.code.toByte() ||
                    head[2] != 'U'.code.toByte() || head[3] != 'F'.code.toByte()
                ) "El archivo no tiene la firma GGUF (magia 'GGUF')."
                else null
            }
            ModelFormat.TFLITE -> {
                // FlatBuffer: bytes 4-7 = 'TFL3'
                val head = readHead(file, 8)
                if (head.size < 8 || head[4] != 'T'.code.toByte() || head[5] != 'F'.code.toByte() ||
                    head[6] != 'L'.code.toByte() || head[7] != '3'.code.toByte()
                ) "El archivo no tiene la firma TFLite ('TFL3')."
                else null
            }
            ModelFormat.UNKNOWN -> "Formato desconocido."
        }
    } catch (e: IOException) {
        "No se pudo leer el archivo para validar su firma: ${e.message}"
    }

    private fun readHead(file: File, n: Int): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(n)
            val read = raf.read(buf)
            return if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    }
}
