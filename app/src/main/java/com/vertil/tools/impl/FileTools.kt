package com.vertil.tools.impl

import com.vertil.core.VertilResult
import com.vertil.permissions.PermissionCapability
import com.vertil.storage.FileEngine
import com.vertil.tools.Tool
import com.vertil.tools.ToolInput
import com.vertil.tools.ToolResult

class FileSearchTool(private val engine: FileEngine) : Tool {
    override val id = "file_search"
    override val name = "Buscar archivos"
    override val description = "Busca archivos por nombre dentro de un directorio. Parámetros: 'root', 'query'."
    override val capabilities = listOf(PermissionCapability.SEARCH_FILES)

    override fun validate(input: ToolInput): Boolean =
        input.arg("root") != null && input.arg("query") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val root = input.arg("root")!!
        val query = input.arg("query")!!
        val result = engine.search(root, query)
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf(
                        "query" to query,
                        "count" to result.value.size.toString(),
                        "results" to result.value.joinToString("\n") { e ->
                            "${if (e.isDirectory) "[DIR] " else ""}${e.name} — ${e.path}"
                        }
                    ),
                    message = "${result.value.size} archivos encontrados")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "SEARCH_FAILED", message = result.message)
            )
        }
    }
}

class FileMoveTool(private val engine: FileEngine) : Tool {
    override val id = "file_move"
    override val name = "Mover archivo"
    override val description = "Mueve un archivo a otra carpeta. Parámetros: 'source', 'destination_dir', 'new_name' (opcional)."
    override val capabilities = listOf(PermissionCapability.MOVE_FILE)

    override fun validate(input: ToolInput): Boolean =
        input.arg("source") != null && input.arg("destination_dir") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val src = input.arg("source")!!
        val dst = input.arg("destination_dir")!!
        val result = engine.moveFile(src, dst, input.arg("new_name"))
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf("source" to src, "destination" to result.value),
                    message = "Archivo movido a ${result.value}")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "MOVE_FAILED", message = result.message)
            )
        }
    }
}

class FileCopyTool(private val engine: FileEngine) : Tool {
    override val id = "file_copy"
    override val name = "Copiar archivo"
    override val description = "Copia un archivo. Parámetros: 'source', 'destination_dir', 'new_name' (opcional)."
    override val capabilities = listOf(PermissionCapability.COPY_FILE)

    override fun validate(input: ToolInput): Boolean =
        input.arg("source") != null && input.arg("destination_dir") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val src = input.arg("source")!!
        val dst = input.arg("destination_dir")!!
        val result = engine.copyFile(src, dst, input.arg("new_name"))
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf("source" to src, "destination" to result.value),
                    message = "Archivo copiado a ${result.value}")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "COPY_FAILED", message = result.message)
            )
        }
    }
}

class FileRenameTool(private val engine: FileEngine) : Tool {
    override val id = "file_rename"
    override val name = "Renombrar archivo"
    override val description = "Renombra un archivo. Parámetros: 'source', 'new_name'."
    override val capabilities = listOf(PermissionCapability.RENAME_FILE)

    override fun validate(input: ToolInput): Boolean =
        input.arg("source") != null && input.arg("new_name") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val result = engine.renameFile(input.arg("source")!!, input.arg("new_name")!!)
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf("new_path" to result.value),
                    message = "Renombrado a ${result.value}")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "RENAME_FAILED", message = result.message)
            )
        }
    }
}

class FolderCreateTool(private val engine: FileEngine) : Tool {
    override val id = "folder_create"
    override val name = "Crear carpeta"
    override val description = "Crea una carpeta. Parámetros: 'parent', 'name'."
    override val capabilities = listOf(PermissionCapability.CREATE_FOLDER)

    override fun validate(input: ToolInput): Boolean =
        input.arg("parent") != null && input.arg("name") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val result = engine.createFolder(input.arg("parent")!!, input.arg("name")!!)
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf("path" to result.value),
                    message = "Carpeta creada: ${result.value}")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "CREATE_FAILED", message = result.message)
            )
        }
    }
}

class FileHashTool(private val engine: FileEngine) : Tool {
    override val id = "file_hash"
    override val name = "Hash de archivo"
    override val description = "Calcula SHA-256 de un archivo. Parámetros: 'path'."
    override val capabilities = listOf(PermissionCapability.READ_FILE_CONTENT)

    override fun validate(input: ToolInput): Boolean = input.arg("path") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val result = engine.hashSha256(input.arg("path")!!)
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf("sha256" to result.value),
                    message = "SHA-256: ${result.value}")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "HASH_FAILED", message = result.message)
            )
        }
    }
}

class FileListTool(private val engine: FileEngine) : Tool {
    override val id = "file_list"
    override val name = "Listar carpeta"
    override val description = "Lista el contenido de un directorio. Parámetros: 'path'."
    override val capabilities = listOf(PermissionCapability.LIST_DIRECTORIES)

    override fun validate(input: ToolInput): Boolean = input.arg("path") != null

    override suspend fun execute(input: ToolInput): VertilResult<ToolResult> {
        val result = engine.list(input.arg("path")!!)
        return when (result) {
            is VertilResult.Success -> VertilResult.ok(
                ToolResult.Success(operation = id,
                    data = mapOf(
                        "count" to result.value.size.toString(),
                        "entries" to result.value.joinToString("\n") { e ->
                            "${if (e.isDirectory) "[DIR] " else ""}${e.name} (${e.sizeHuman})"
                        }
                    ),
                    message = "${result.value.size} entradas")
            )
            is VertilResult.Failure -> VertilResult.ok(
                ToolResult.Error(operation = id, code = "LIST_FAILED", message = result.message)
            )
        }
    }
}
