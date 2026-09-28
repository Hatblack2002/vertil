package com.vertil.model.packaging

import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.model.ModelFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Concepto de paquete de modelo (especificación §4): un modelo ya NO es solo
 * "archivo.onnx" sino un directorio que agrupa el grafo y sus recursos:
 *
 * ```
 * models/<paquete>/
 * ├── model.onnx              (OBLIGATORIO)
 * ├── tokenizer.json          (OBLIGATORIO para chat)
 * ├── tokenizer_config.json   (opcional: chat_template, eos_token…)
 * ├── config.json             (opcional: arquitectura, vocab_size…)
 * ├── generation_config.json  (opcional: eos_token_id, max_new_tokens…)
 * ├── special_tokens_map.json (opcional)
 * ├── metadata.json           (escrito por VERTIL tras la importación)
 * └── …otros recursos estándar de HF (merges.txt, vocab.json…)
 * ```
 *
 * NADA se asume por modelo: el cargador DESCUBRE qué archivos existen y
 * genera un error técnico claro si falta un recurso obligatorio. Un modelo
 * .onnx importado aislado (Fase 1) también es válido: sus archivos auxiliares
 * se buscan en el mismo directorio.
 */
data class ModelPackage(
    /** Directorio raíz del paquete. */
    val dir: File,
    /** Grafo ONNX (único). */
    val onnxFile: File,
    /** tokenizer.json — obligatorio para generación conversacional. */
    val tokenizerFile: File,
    val tokenizerConfigFile: File? = null,
    val configFile: File? = null,
    val generationConfigFile: File? = null,
    val specialTokensMapFile: File? = null,
    val metadataFile: File? = null,
    /** Otros archivos estándar HF presentes (merges.txt, vocab.json…). */
    val extraFiles: List<File> = emptyList()
) {
    val name: String get() = dir.name
    val totalSizeBytes: Long
        get() = allFiles.sumOf { it.length() }
    val allFiles: List<File>
        get() = (listOfNotNull(
            onnxFile, tokenizerFile, tokenizerConfigFile, configFile,
            generationConfigFile, specialTokensMapFile, metadataFile
        ) + extraFiles).distinct()
}

/**
 * Descubrimiento de paquetes desde el disco. Reglas:
 *  - [discover] acepta un directorio de paquete o un archivo .onnx aislado
 *    (sus auxiliares se buscan junto a él);
 *  - recursos obligatorios: EXACTAMENTE un .onnx + tokenizer.json;
 *  - si falta un recurso obligatorio → Failure con la lista explícita
 *    (nunca proseguir a ciegas — especificación §4/§8).
 */
object ModelPackageLoader {

    private const val TAG = "ModelPackage"

    /** Nombres de recursos auxiliares reconocidos (estándar HF + VERTIL). */
    val AUX_FILE_NAMES = setOf(
        "tokenizer.json", "tokenizer_config.json", "config.json",
        "generation_config.json", "special_tokens_map.json", "metadata.json",
        "merges.txt", "vocab.json", "quantize_config.json", "added_tokens.json",
        "chat_template.jinja", "processor_config.json", "preprocessor_config.json"
    )

    fun isAuxFileName(fileName: String): Boolean = fileName.lowercase() in AUX_FILE_NAMES

    /**
     * Descubre un paquete a partir de un directorio o de un archivo .onnx.
     */
    fun discover(location: File): VertilResult<ModelPackage> {
        if (!location.exists()) {
            return VertilResult.fail(
                "La ubicación del modelo no existe: ${location.name}",
                module = TAG, code = "LOCATION_NOT_FOUND"
            )
        }
        val dir = if (location.isDirectory) location
        else location.parentFile ?: return VertilResult.fail(
            "No se pudo determinar el directorio del paquete.", module = TAG, code = "NO_PARENT_DIR"
        )

        // ---- 1. Localizar el grafo ONNX (obligatorio) ----
        val onnxFiles = dir.listFiles { f -> f.isFile && ModelFormat.fromExtension(f.name) == ModelFormat.ONNX }
            ?.sortedBy { it.name } ?: emptyList()
        if (onnxFiles.isEmpty()) {
            return VertilResult.fail(
                "Paquete incompleto: falta el archivo del modelo (.onnx) en '${dir.name}'.",
                module = TAG, code = "MISSING_REQUIRED_RESOURCE"
            )
        }
        val onnx = onnxFiles.firstOrNull { it.name.equals("model.onnx", ignoreCase = true) }
            ?: onnxFiles.first()

        // ---- 2. Recursos auxiliares presentes ----
        val files = dir.listFiles { f -> f.isFile && f != onnx }?.sortedBy { it.name } ?: emptyList()
        fun find(name: String): File? = files.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val tokenizer = find("tokenizer.json")
        val knownExtras = setOf(
            "tokenizer.json", "tokenizer_config.json", "config.json",
            "generation_config.json", "special_tokens_map.json", "metadata.json"
        )
        val extra = files.filter { it.name.lowercase() !in knownExtras }

        // ---- 3. Recurso obligatorio: tokenizer.json ----
        if (tokenizer == null) {
            return VertilResult.fail(
                "Paquete incompleto: falta 'tokenizer.json' junto a '${onnx.name}' " +
                    "en '${dir.name}'. Importa también los archivos auxiliares del modelo " +
                    "(tokenizer.json, tokenizer_config.json, config.json, generation_config.json).",
                module = TAG, code = "MISSING_REQUIRED_RESOURCE"
            )
        }

        return VertilResult.ok(
            ModelPackage(
                dir = dir,
                onnxFile = onnx,
                tokenizerFile = tokenizer,
                tokenizerConfigFile = find("tokenizer_config.json"),
                configFile = find("config.json"),
                generationConfigFile = find("generation_config.json"),
                specialTokensMapFile = find("special_tokens_map.json"),
                metadataFile = find("metadata.json"),
                extraFiles = extra
            )
        )
    }

    /** Descripción legible del paquete (logs / UI técnica). */
    fun describe(pkg: ModelPackage): String = buildString {
        appendLine("Paquete: ${pkg.name} (${com.vertil.model.importer.ModelImporter.formatBytes(pkg.totalSizeBytes)})")
        appendLine("  grafo:            ${pkg.onnxFile.name}")
        appendLine("  tokenizer:        ${pkg.tokenizerFile.name}")
        appendLine("  tokenizer_config: ${pkg.tokenizerConfigFile?.name ?: "—"}")
        appendLine("  config:           ${pkg.configFile?.name ?: "—"}")
        appendLine("  generation:       ${pkg.generationConfigFile?.name ?: "—"}")
        if (pkg.extraFiles.isNotEmpty()) appendLine("  extra:            ${pkg.extraFiles.joinToString { it.name }}")
    }.trimEnd()

    /**
     * Escribe/actualiza metadata.json del paquete (inventario con hashes).
     * [entries] = archivos ya importados con su SHA-256.
     */
    fun writeMetadata(
        packageDir: File,
        packageName: String,
        entries: List<PackageFileEntry>,
        importedAt: Long = System.currentTimeMillis()
    ): File {
        val json = Json { prettyPrint = true }
        val obj = buildJsonObject {
            put("name", packageName)
            put("importedAt", importedAt)
            put("runtime", "onnx")
            put("files", buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("name", e.file.name)
                        put("role", e.role)
                        put("sizeBytes", e.sizeBytes)
                        put("sha256", e.sha256)
                    })
                }
            })
        }
        val out = File(packageDir, "metadata.json")
        out.writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
        return out
    }

    data class PackageFileEntry(
        val file: File,
        val role: String,       // "graph" | "tokenizer" | "config" | "aux"
        val sizeBytes: Long,
        val sha256: String
    )
}
