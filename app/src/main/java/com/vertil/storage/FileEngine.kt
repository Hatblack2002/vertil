package com.vertil.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * FileEngine: abstracción sobre DocumentFile / SAF + almacenamiento interno.
 *
 * No asume acceso absoluto al sistema de archivos. Trabaja con:
 *  - internal storage de la app (sin permisos)
 *  - carpetas públicas vía MediaStore cuando los permisos lo permiten
 *  - árboles SAF cuando el usuario concede acceso a una carpeta
 */
class FileEngine(private val context: Context) {

    /**
     * Lista contenido de un directorio dado como ruta (internal) o como Uri SAF.
     */
    suspend fun list(pathOrUri: String): VertilResult<List<FileEntry>> = withContext(Dispatchers.IO) {
        try {
            if (pathOrUri.startsWith("content://")) {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(pathOrUri))
                    ?: return@withContext VertilResult.fail("Invalid SAF URI: $pathOrUri", module = "FileEngine")
                val entries = tree.listFiles().map { doc ->
                    FileEntry(
                        name = doc.name ?: "(sin nombre)",
                        path = doc.uri.toString(),
                        isDirectory = doc.isDirectory,
                        sizeBytes = if (doc.isFile) doc.length() else 0L,
                        lastModified = doc.lastModified()
                    )
                }
                VertilResult.ok(entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name }))
            } else {
                val dir = File(pathOrUri)
                if (!dir.exists() || !dir.isDirectory) {
                    return@withContext VertilResult.fail("Not a directory: $pathOrUri", module = "FileEngine")
                }
                val entries = dir.listFiles()?.map { f ->
                    FileEntry(
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = f.isDirectory,
                        sizeBytes = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                } ?: emptyList()
                VertilResult.ok(entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name }))
            }
        } catch (t: Throwable) {
            VertilLog.e("FileEngine", "list failed: $pathOrUri", t)
            VertilResult.fail("list failed: ${t.message}", t, "FileEngine")
        }
    }

    /**
     * Busca archivos por nombre dentro de un directorio (recursivo, hasta maxDepth).
     */
    suspend fun search(root: String, query: String, maxDepth: Int = 5): VertilResult<List<FileEntry>> = withContext(Dispatchers.IO) {
        try {
            val results = mutableListOf<FileEntry>()
            val q = query.lowercase().trim()
            if (q.isEmpty()) return@withContext VertilResult.ok(emptyList())

            if (root.startsWith("content://")) {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(root)) ?: return@withContext VertilResult.fail(
                    "Invalid SAF URI", module = "FileEngine"
                )
                searchSaf(tree, q, 0, maxDepth, results)
            } else {
                val dir = File(root)
                if (dir.exists() && dir.isDirectory) {
                    searchFilesystem(dir, q, 0, maxDepth, results)
                }
            }
            VertilResult.ok(results.take(500))
        } catch (t: Throwable) {
            VertilResult.fail("search failed: ${t.message}", t, "FileEngine")
        }
    }

    private fun searchFilesystem(dir: File, q: String, depth: Int, maxDepth: Int, results: MutableList<FileEntry>) {
        if (depth > maxDepth) return
        dir.listFiles()?.forEach { f ->
            if (f.name.lowercase().contains(q)) {
                results.add(
                    FileEntry(f.name, f.absolutePath, f.isDirectory, if (f.isFile) f.length() else 0L, f.lastModified())
                )
            }
            if (f.isDirectory && !f.name.startsWith(".")) {
                searchFilesystem(f, q, depth + 1, maxDepth, results)
            }
        }
    }

    private fun searchSaf(doc: DocumentFile, q: String, depth: Int, maxDepth: Int, results: MutableList<FileEntry>) {
        if (depth > maxDepth) return
        doc.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name.lowercase().contains(q)) {
                results.add(
                    FileEntry(name, child.uri.toString(), child.isDirectory, if (child.isFile) child.length() else 0L, child.lastModified())
                )
            }
            if (child.isDirectory) searchSaf(child, q, depth + 1, maxDepth, results)
        }
    }

    /**
     * Calcula hash SHA-256 de un archivo.
     */
    suspend fun hashSha256(pathOrUri: String): VertilResult<String> = withContext(Dispatchers.IO) {
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val stream = if (pathOrUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(pathOrUri))
                    ?: return@withContext VertilResult.fail("Cannot open stream", module = "FileEngine")
            } else {
                java.io.FileInputStream(pathOrUri)
            }
            stream.use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf); if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            VertilResult.ok(hex)
        } catch (t: Throwable) {
            VertilResult.fail("hash failed: ${t.message}", t, "FileEngine")
        }
    }

    /**
     * Crea una carpeta en el internal storage de la app o en una ruta SAF.
     */
    suspend fun createFolder(parentPathOrUri: String, name: String): VertilResult<String> = withContext(Dispatchers.IO) {
        try {
            if (parentPathOrUri.startsWith("content://")) {
                val parent = DocumentFile.fromTreeUri(context, Uri.parse(parentPathOrUri))
                    ?: return@withContext VertilResult.fail("Invalid SAF URI", module = "FileEngine")
                val exists = parent.findFile(name)
                if (exists != null) {
                    return@withContext VertilResult.ok(exists.uri.toString())
                }
                val created = parent.createDirectory(name)
                    ?: return@withContext VertilResult.fail("Cannot create folder in SAF", module = "FileEngine")
                VertilResult.ok(created.uri.toString())
            } else {
                val dir = File(parentPathOrUri, name)
                if (dir.exists()) return@withContext VertilResult.ok(dir.absolutePath)
                if (!dir.mkdirs()) return@withContext VertilResult.fail("Cannot mkdirs: ${dir.absolutePath}", module = "FileEngine")
                VertilResult.ok(dir.absolutePath)
            }
        } catch (t: Throwable) {
            VertilResult.fail("createFolder failed: ${t.message}", t, "FileEngine")
        }
    }

    /**
     * Mueve un archivo dentro del internal storage.
     * Para mover entre árboles SAF se requiere copy+delete (más lento).
     */
    suspend fun moveFile(srcPath: String, dstDir: String, newName: String? = null): VertilResult<String> = withContext(Dispatchers.IO) {
        try {
            val src = File(srcPath)
            if (!src.exists()) return@withContext VertilResult.fail("Source not found: $srcPath", module = "FileEngine")
            val targetName = newName ?: src.name
            val dst = File(dstDir, targetName)
            if (dst.exists()) return@withContext VertilResult.fail("Destination exists: ${dst.absolutePath}", module = "FileEngine")
            if (!src.renameTo(dst)) {
                // Fallback: copy + delete
                src.copyTo(dst, overwrite = false)
                src.delete()
            }
            VertilResult.ok(dst.absolutePath)
        } catch (t: Throwable) {
            VertilResult.fail("move failed: ${t.message}", t, "FileEngine")
        }
    }

    /**
     * Copia un archivo.
     */
    suspend fun copyFile(srcPath: String, dstDir: String, newName: String? = null): VertilResult<String> = withContext(Dispatchers.IO) {
        try {
            val src = File(srcPath)
            if (!src.exists()) return@withContext VertilResult.fail("Source not found: $srcPath", module = "FileEngine")
            val targetName = newName ?: src.name
            val dst = File(dstDir, targetName)
            src.copyTo(dst, overwrite = false)
            VertilResult.ok(dst.absolutePath)
        } catch (t: Throwable) {
            VertilResult.fail("copy failed: ${t.message}", t, "FileEngine")
        }
    }

    /**
     * Renombra un archivo.
     */
    suspend fun renameFile(path: String, newName: String): VertilResult<String> = withContext(Dispatchers.IO) {
        try {
            val src = File(path)
            if (!src.exists()) return@withContext VertilResult.fail("File not found: $path", module = "FileEngine")
            val dst = File(src.parentFile, newName)
            if (!src.renameTo(dst)) return@withContext VertilResult.fail("Rename failed", module = "FileEngine")
            VertilResult.ok(dst.absolutePath)
        } catch (t: Throwable) {
            VertilResult.fail("rename failed: ${t.message}", t, "FileEngine")
        }
    }
}

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val sizeHuman: String get() = ModelInfo_humanSize(sizeBytes)
}

// helper sin importar la clase modelo
private fun ModelInfo_humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
