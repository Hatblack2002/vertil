package com.vertil.model

import com.vertil.core.VertilResult
import com.vertil.core.error.ErrorManager
import com.vertil.core.log.VertilLog
import com.vertil.model.importer.ModelFileValidator
import com.vertil.model.importer.ModelImporter
import com.vertil.model.packaging.ModelPackageLoader
import com.vertil.model.runtime.OnnxRuntimeInspector
import com.vertil.model.runtime.engine.ChatTurn
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

    /** Validación profunda: ORT debe poder abrir el grafo durante la importación. */
    private val deepValidator = ModelFileValidator.DeepValidator { file, format ->
        if (format == ModelFormat.ONNX) OnnxRuntimeInspector.validateOpenable(file) else null
    }
    private val importer = ModelImporter(VertilDatabase.modelsDir, deepValidator)
    private val modelsDir: File get() = VertilDatabase.modelsDir

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

    suspend fun generate(
        prompt: String,
        systemPrompt: String,
        history: List<ChatTurn> = emptyList(),
        maxNewTokens: Int? = null
    ): VertilResult<GenerationResult> {
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
        val params = GenerationParams(
            prompt = prompt,
            systemPrompt = systemPrompt,
            history = history,
            maxNewTokens = maxNewTokens
        )
        return r.generate(params)
    }

    fun cancelGeneration() {
        _activeRuntime.value.cancel()
    }

    fun setStreamListener(listener: ((String) -> Unit)?) {
        _activeRuntime.value.setStreamListener(listener)
    }

    // ==================== Importación de PAQUETES (§4) ====================

    /** Entrada de importación desacoplada de Android (testeable en JVM). */
    data class PackageEntryInput(
        val displayName: String,
        val expectedSize: Long?,
        val openStream: () -> java.io.InputStream
    )

    /**
     * Importa un PAQUETE multi-archivo (model.onnx + tokenizer.json + configs…).
     * Reglas (especificación §4):
     *  - exactamente UN .onnx;
     *  - auxiliares reconocidos van al mismo directorio;
     *  - error técnico claro si falta el grafo o se envían archivos desconocidos.
     */
    suspend fun importPackage(entries: List<PackageEntryInput>, packageName: String? = null): VertilResult<ModelInfo> {
        return try {
            if (entries.isEmpty()) return VertilResult.fail(
                "No se seleccionaron archivos.", module = "ModelManager", code = "EMPTY_SELECTION"
            )

            val onnxEntries = entries.filter {
                ModelFormat.fromExtension(it.displayName) == ModelFormat.ONNX
            }
            val auxEntries = entries.filter {
                ModelFormat.fromExtension(it.displayName) == ModelFormat.UNKNOWN &&
                    ModelPackageLoader.isAuxFileName(it.displayName)
            }
            val unknown = entries.filter { it !in onnxEntries && it !in auxEntries }
            if (unknown.isNotEmpty()) {
                return VertilResult.fail(
                    "Archivos no reconocidos: ${unknown.joinToString { it.displayName }}. " +
                        "VERTIL acepta un .onnx y recursos auxiliares del modelo " +
                        "(tokenizer.json, tokenizer_config.json, config.json, generation_config.json…).",
                    module = "ModelManager", code = "UNKNOWN_FILE"
                )
            }
            if (onnxEntries.size != 1) {
                return VertilResult.fail(
                    "Un paquete debe contener EXACTAMENTE un archivo .onnx (recibidos: " +
                        "${onnxEntries.size}). Selecciona el grafo y sus auxiliares juntos.",
                    module = "ModelManager", code = "INVALID_PACKAGE"
                )
            }
            if (auxEntries.none { it.displayName.equals("tokenizer.json", true) }) {
                return VertilResult.fail(
                    "Falta 'tokenizer.json': sin él no hay tokenización real ni chat. " +
                        "Importa el paquete completo del modelo.",
                    module = "ModelManager", code = "MISSING_TOKENIZER"
                )
            }

            val onnxEntry = onnxEntries.first()
            val pkgName = sanitizePackageName(
                packageName ?: onnxEntry.displayName.removeSuffix(".onnx").removeSuffix("_q4f16").removeSuffix("_fp16")
            )
            val pkgDir = resolvePackageDir(pkgName)

            VertilLog.i("ModelManager", "Importando paquete '$pkgName' (${entries.size} archivos)")

            // 1. Grafo ONNX (validación firma + ORT profunda).
            val onnxResult = importer.import(
                displayName = onnxEntry.displayName,
                format = ModelFormat.ONNX,
                expectedSize = onnxEntry.expectedSize,
                openStream = onnxEntry.openStream,
                packageDir = pkgDir
            )
            val onnxImported = when (onnxResult) {
                is VertilResult.Success -> onnxResult.value
                is VertilResult.Failure -> return VertilResult.fail(
                    onnxResult.message, onnxResult.cause, "ModelManager", onnxResult.code
                )
            }

            // 2. Auxiliares (JSON validado).
            val auxImported = ArrayList<ModelImporter.ImportedFile>()
            for (aux in auxEntries) {
                when (val r = importer.importAux(aux.displayName, aux.expectedSize, aux.openStream, pkgDir)) {
                    is VertilResult.Success -> auxImported.add(r.value)
                    is VertilResult.Failure -> return VertilResult.fail(
                        "${aux.displayName}: ${r.message}", r.cause, "ModelManager", r.code
                    )
                }
            }

            // 3. metadata.json del paquete.
            val metadataEntries = (listOf(onnxImported) + auxImported).map {
                ModelPackageLoader.PackageFileEntry(
                    file = it.file,
                    role = if (it == onnxImported) "graph" else "aux",
                    sizeBytes = it.sizeBytes,
                    sha256 = it.sha256
                )
            }
            ModelPackageLoader.writeMetadata(pkgDir, pkgName, metadataEntries)

            // 4. Registro Room (solo tras copia+validación completas — Fase 1).
            val totalSize = metadataEntries.sumOf { it.sizeBytes }
            val info = ModelInfo(
                id = "model_${System.currentTimeMillis()}",
                name = pkgName,
                filePath = onnxImported.file.absolutePath,
                format = ModelFormat.ONNX,
                sizeBytes = totalSize,
                quantization = detectQuantization(onnxImported.displayName),
                contextLength = null,
                hashSha256 = onnxImported.sha256,
                importedAt = System.currentTimeMillis(),
                isActive = false,
                state = ModelState.INSTALLED
            )
            db.modelDao().insert(info.toEntity())
            VertilLog.i("ModelManager", "Paquete importado: ${info.name} (${info.sizeHuman})")
            VertilResult.ok(info)
        } catch (t: Throwable) {
            errorManager.report("ModelManager", "importPackage failed: ${t.message}", t)
            VertilResult.fail("Import de paquete fallido: ${t.message}", t, "ModelManager")
        }
    }

    /**
     * Añade archivos auxiliares al modelo ONNX ACTIVO: si vive suelto en
     * models/, se promueve a paquete (directorio propio) y los auxiliares se
     * importan junto a él.
     */
    suspend fun attachAuxToActiveModel(entries: List<PackageEntryInput>): VertilResult<ModelInfo> {
        return try {
            val entity = db.modelDao().getActive() ?: return VertilResult.fail(
                "No hay modelo activo: activa primero el .onnx y luego importa sus auxiliares.",
                module = "ModelManager", code = "NO_ACTIVE_MODEL"
            )
            if (entity.format != ModelFormat.ONNX.name) return VertilResult.fail(
                "Los auxiliares solo aplican a modelos ONNX.",
                module = "ModelManager", code = "NOT_ONNX"
            )
            val auxEntries = entries.filter { ModelPackageLoader.isAuxFileName(it.displayName) }
            if (auxEntries.isEmpty()) return VertilResult.fail(
                "No se reconoció ningún archivo auxiliar (tokenizer.json, config.json…).",
                module = "ModelManager", code = "NO_AUX_FILES"
            )

            val onnxFile = File(entity.filePath)
            if (!onnxFile.exists()) return VertilResult.fail(
                "El archivo del modelo activo no existe.",
                module = "ModelManager", code = "FILE_NOT_FOUND"
            )

            // Promover a paquete si el .onnx está suelto en models/ raíz.
            val pkgDir: File = if (onnxFile.parentFile == modelsDir) {
                val dir = resolvePackageDir(sanitizePackageName(entity.name))
                if (!onnxFile.renameTo(File(dir, onnxFile.name))) {
                    return VertilResult.fail(
                        "No se pudo mover el modelo al directorio de paquete.",
                        module = "ModelManager", code = "RENAME_FAILED"
                    )
                }
                dir
            } else {
                onnxFile.parentFile!!
            }

            val imported = ArrayList<ModelImporter.ImportedFile>()
            for (aux in auxEntries) {
                when (val r = importer.importAux(aux.displayName, aux.expectedSize, aux.openStream, pkgDir)) {
                    is VertilResult.Success -> imported.add(r.value)
                    is VertilResult.Failure -> return VertilResult.fail(
                        "${aux.displayName}: ${r.message}", r.cause, "ModelManager", r.code
                    )
                }
            }

            val newOnnxPath = File(pkgDir, onnxFile.name)
            // metadata.json: inventario completo del directorio (grafo + auxiliares).
            val allFiles = (pkgDir.listFiles { f -> f.isFile && f.extension != "json" }?.toList() ?: emptyList())
                .plus(imported.map { it.file }).distinct()
            ModelPackageLoader.writeMetadata(
                pkgDir, pkgDir.name,
                allFiles.map {
                    ModelPackageLoader.PackageFileEntry(it, if (it.name.endsWith(".onnx")) "graph" else "aux", it.length(), "-")
                }
            )

            val updated = entity.copy(
                filePath = newOnnxPath.absolutePath,
                sizeBytes = entity.sizeBytes + imported.sumOf { it.sizeBytes }
            )
            db.modelDao().insert(updated) // misma PK → reemplaza
            VertilLog.i("ModelManager", "Auxiliares añadidos a '${entity.name}': " +
                imported.joinToString { it.displayName })
            VertilResult.ok(updated.toModelInfo())
        } catch (t: Throwable) {
            errorManager.report("ModelManager", "attachAux failed: ${t.message}", t)
            VertilResult.fail("Añadir auxiliares fallido: ${t.message}", t, "ModelManager")
        }
    }

    private fun sanitizePackageName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return cleaned.ifEmpty { "paquete_${System.currentTimeMillis()}" }.take(64)
    }

    private fun resolvePackageDir(baseName: String): File {
        var dir = File(modelsDir, baseName)
        var i = 2
        while (dir.exists() && dir.listFiles()?.isNotEmpty() == true) {
            dir = File(modelsDir, "${baseName}_$i"); i++
        }
        dir.mkdirs()
        return dir
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
