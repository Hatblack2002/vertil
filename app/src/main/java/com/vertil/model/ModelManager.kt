package com.vertil.model

import com.vertil.core.VertilResult
import com.vertil.core.error.ErrorManager
import com.vertil.core.log.VertilLog
import com.vertil.persistence.VertilDatabase
import com.vertil.persistence.entities.ModelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest

/**
 * ModelManager: orquesta los modelos locales importados + el runtime activo.
 *
 * Responsabilidades:
 *  - Detectar modelos importados (Room).
 *  - Importar modelos desde una ruta/archivo.
 *  - Eliminar modelos.
 *  - Seleccionar modelo activo.
 *  - Cargar/descargar modelo activo en memoria (usando el runtime apropiado).
 *  - Verificar integridad mediante hash SHA-256.
 *  - Exponer el estado observable para la UI.
 *
 * No depende de ningún runtime concreto: usa [ModelRuntimeRegistry] para
 * seleccionar el runtime adecuado al formato del modelo.
 */
class ModelManager(
    private val db: VertilDatabase
) {
    private val errorManager = ErrorManager()

    private val _activeRuntime = MutableStateFlow<LocalModel>(ModelRuntimeRegistry.mock)
    val activeRuntime: StateFlow<LocalModel> = _activeRuntime.asStateFlow()

    val models: Flow<List<ModelInfo>> = db.modelDao().observeAll().map { entities ->
        entities.map { it.toModelInfo() }.sortedByDescending { it.importedAt }
    }

    suspend fun importFromFile(path: String, name: String? = null): VertilResult<ModelInfo> {
        return try {
            val src = File(path)
            if (!src.exists() || !src.canRead()) {
                return VertilResult.fail(
                    "Archivo no accesible: $path",
                    module = "ModelManager", code = "FILE_NOT_FOUND"
                )
            }
            val format = ModelFormat.fromExtension(src.name)
            if (format == ModelFormat.UNKNOWN) {
                return VertilResult.fail(
                    "Formato no soportado: ${src.name}",
                    module = "ModelManager", code = "UNSUPPORTED_FORMAT"
                )
            }
            // Copiar al internal storage de la app
            val target = File(VertilDatabase.modelsDir, "${System.currentTimeMillis()}_${src.name}")
            src.copyTo(target, overwrite = true)
            val hash = sha256(target)
            val info = ModelInfo(
                id = "model_${System.currentTimeMillis()}",
                name = name ?: src.nameWithoutExtension,
                filePath = target.absolutePath,
                format = format,
                sizeBytes = target.length(),
                quantization = detectQuantization(src.name),
                contextLength = null,
                hashSha256 = hash,
                importedAt = System.currentTimeMillis(),
                isActive = false,
                state = ModelState.INSTALLED,
                capabilities = ModelCapabilities()
            )
            db.modelDao().insert(info.toEntity())
            VertilLog.i("ModelManager", "Modelo importado: ${info.name} (${info.sizeHuman}, $format)")
            VertilResult.ok(info)
        } catch (t: Throwable) {
            errorManager.report("ModelManager", "importFromFile failed: ${t.message}", t)
            VertilResult.fail("Import failed: ${t.message}", t, "ModelManager")
        }
    }

    suspend fun delete(id: String): VertilResult<Unit> {
        return try {
            val entity = db.modelDao().getById(id) ?: return VertilResult.fail(
                "Modelo no encontrado: $id", module = "ModelManager", code = "NOT_FOUND"
            )
            // Si está activo, descargar primero
            if (entity.isActive) {
                _activeRuntime.value.unload()
                _activeRuntime.value = ModelRuntimeRegistry.mock
            }
            File(entity.filePath).takeIf { it.exists() }?.delete()
            db.modelDao().delete(id)
            VertilLog.i("ModelManager", "Modelo eliminado: $id")
            VertilResult.ok(Unit)
        } catch (t: Throwable) {
            VertilResult.fail("Delete failed: ${t.message}", t, "ModelManager")
        }
    }

    suspend fun setActive(id: String): VertilResult<ModelInfo> {
        return try {
            val entity = db.modelDao().getById(id) ?: return VertilResult.fail(
                "Modelo no encontrado: $id", module = "ModelManager", code = "NOT_FOUND"
            )
            // Desactivar todos
            db.modelDao().clearActive()
            db.modelDao().setActive(id, true)
            VertilLog.i("ModelManager", "Modelo activo: ${entity.name}")
            VertilResult.ok(entity.toModelInfo().copy(isActive = true))
        } catch (t: Throwable) {
            VertilResult.fail("setActive failed: ${t.message}", t, "ModelManager")
        }
    }

    suspend fun loadActive(): VertilResult<ModelInfo> {
        val activeEntity = db.modelDao().getActive()
            ?: return VertilResult.fail(
                "No hay modelo activo. Selecciona uno en la pestaña Modelos.",
                module = "ModelManager", code = "NO_ACTIVE_MODEL"
            )
        val info = activeEntity.toModelInfo()
        val runtime = ModelRuntimeRegistry.forFormat(info.format)

        // Si ya hay runtime activo distinto, descargarlo
        if (_activeRuntime.value.runtimeId != runtime.runtimeId
            && _activeRuntime.value.state in setOf(ModelState.READY, ModelState.GENERATING)) {
            _activeRuntime.value.unload()
        }
        _activeRuntime.value = runtime
        val result = runtime.load(info)
        return when (result) {
            is VertilResult.Success -> {
                db.modelDao().updateState(info.id, ModelState.READY.name, null)
                VertilResult.ok(result.value)
            }
            is VertilResult.Failure -> {
                db.modelDao().updateState(info.id, ModelState.ERROR.name, result.message)
                result
            }
        }
    }

    suspend fun unloadActive(): VertilResult<Unit> {
        val r = _activeRuntime.value
        val result = r.unload()
        _activeRuntime.value = ModelRuntimeRegistry.mock
        // Marcar el modelo como INSTALLED en DB
        val activeEntity = db.modelDao().getActive()
        if (activeEntity != null) {
            db.modelDao().updateState(activeEntity.id, ModelState.INSTALLED.name, null)
        }
        return result
    }

    suspend fun verifyHash(id: String): VertilResult<Boolean> {
        val entity = db.modelDao().getById(id) ?: return VertilResult.fail(
            "Modelo no encontrado", module = "ModelManager", code = "NOT_FOUND"
        )
        val file = File(entity.filePath)
        if (!file.exists()) return VertilResult.fail(
            "Archivo no existe", module = "ModelManager", code = "FILE_NOT_FOUND"
        )
        val currentHash = sha256(file)
        val ok = currentHash == entity.hashSha256
        return VertilResult.ok(ok)
    }

    suspend fun generate(prompt: String, systemPrompt: String): VertilResult<GenerationResult> {
        val runtime = _activeRuntime.value
        if (runtime.state != ModelState.READY) {
            // Auto-cargar si hay modelo activo
            val loadResult = loadActive()
            if (loadResult is VertilResult.Failure) {
                // Si no hay modelo, usar mock (respuesta clara)
                _activeRuntime.value = ModelRuntimeRegistry.mock
                ModelRuntimeRegistry.mock.load(ModelInfo.UNKNOWN)
            }
        }
        val r = _activeRuntime.value
        val params = GenerationParams(prompt = prompt, systemPrompt = systemPrompt)
        return r.generate(params)
    }

    fun cancelGeneration() {
        _activeRuntime.value.cancel()
    }

    // === Helpers ===

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun detectQuantization(filename: String): String? {
        val lower = filename.lowercase()
        return when {
            "q4_k_m" in lower -> "Q4_K_M"
            "q4_k_s" in lower -> "Q4_K_S"
            "q4_0" in lower -> "Q4_0"
            "q4_1" in lower -> "Q4_1"
            "q5_k_m" in lower -> "Q5_K_M"
            "q5_k_s" in lower -> "Q5_K_S"
            "q8_0" in lower -> "Q8_0"
            "int8" in lower -> "INT8"
            "int4" in lower -> "INT4"
            "fp16" in lower -> "FP16"
            "fp32" in lower -> "FP32"
            else -> null
        }
    }

    // === Mappers ===

    private fun ModelEntity.toModelInfo(): ModelInfo = ModelInfo(
        id = id,
        name = name,
        filePath = filePath,
        format = runCatching { ModelFormat.valueOf(format) }.getOrDefault(ModelFormat.UNKNOWN),
        sizeBytes = sizeBytes,
        quantization = quantization,
        contextLength = contextLength,
        hashSha256 = hashSha256,
        importedAt = importedAt,
        isActive = isActive,
        state = runCatching { ModelState.valueOf(state) }.getOrDefault(ModelState.INSTALLED),
        lastErrorMessage = lastErrorMessage
    )

    private fun ModelInfo.toEntity(): ModelEntity = ModelEntity(
        id = id,
        name = name,
        filePath = filePath,
        format = format.name,
        sizeBytes = sizeBytes,
        quantization = quantization,
        contextLength = contextLength,
        hashSha256 = hashSha256,
        importedAt = importedAt,
        isActive = isActive,
        state = state.name,
        lastErrorMessage = lastErrorMessage
    )
}
