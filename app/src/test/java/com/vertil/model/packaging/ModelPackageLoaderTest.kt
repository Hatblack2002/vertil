package com.vertil.model.packaging

import com.vertil.core.VertilResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TEST 1 — Model package (especificación §19.1).
 *
 * Verifica que el cargador detecta los archivos del modelo, que un .onnx
 * aislado (Fase 1) se resuelve contra su propio directorio y que los recursos
 * obligatorios faltantes producen un error técnico explícito.
 *
 * Usa los archivos REALES del modelo de aceptación (SmolLM2-135M-Instruct)
 * copiados en src/test/resources/smollm2/.
 */
class ModelPackageLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resourceDir(): File {
        val url = javaClass.classLoader!!.getResource("smollm2/tokenizer.json")
            ?: error("Faltan recursos de test smollm2/")
        return File(url.toURI()).parentFile
    }

    @Test
    fun `descubre paquete completo con archivos reales y grafo minimo`() {
        // Los auxiliares REALES del modelo de aceptación + un grafo mínimo con
        // firma ONNX válida (el grafo real de 117MB no se commitea; los tests
        // con grafo real están en OnnxEngineAcceptanceTest).
        val dir = tmp.newFolder("smollm2-full")
        resourceDir().listFiles()?.forEach { it.copyTo(File(dir, it.name)) }
        File(dir, "model.onnx").writeBytes(byteArrayOf(0x08, 0x05, 0x01, 0x00))

        val result = ModelPackageLoader.discover(dir)

        assertTrue("discover debe OK: ${(result as? VertilResult.Failure)?.message}", result.isSuccess())
        val pkg = result.getOrNull()!!
        assertEquals("model.onnx", pkg.onnxFile.name)
        assertEquals("tokenizer.json", pkg.tokenizerFile.name)
        assertEquals("tokenizer_config.json", pkg.tokenizerConfigFile?.name)
        assertEquals("config.json", pkg.configFile?.name)
        assertEquals("generation_config.json", pkg.generationConfigFile?.name)
        assertEquals("special_tokens_map.json", pkg.specialTokensMapFile?.name)
        assertTrue(pkg.totalSizeBytes > 0)
    }

    @Test
    fun `directorio solo con auxiliares exige el grafo onnx`() {
        // El dir de recursos del repo solo trae tokenizer/configs (sin .onnx):
        // el cargador debe exigir el grafo, no inventar nada.
        val result = ModelPackageLoader.discover(resourceDir())

        assertTrue(result.isFailure())
        val f = result as VertilResult.Failure
        assertEquals("MISSING_REQUIRED_RESOURCE", f.code)
        assertTrue(f.message.contains(".onnx"))
    }

    @Test
    fun `descubre paquete al que se le copia un grafo onnx minimo`() {
        // Directorio real de SmolLM2 + grafo "falso pero con firma ONNX válida":
        // solo interesa la detección de archivos (el grafo real se prueba en
        // OnnxEngineAcceptanceTest).
        val dir = tmp.newFolder("smollm2-pkg")
        resourceDir().listFiles()?.forEach { it.copyTo(File(dir, it.name)) }
        File(dir, "model.onnx").writeBytes(byteArrayOf(0x08, 0x05, 0x01, 0x00))

        val result = ModelPackageLoader.discover(dir)

        assertTrue(result.isSuccess())
        val pkg = result.getOrNull()!!
        assertEquals("model.onnx", pkg.onnxFile.name)
        assertTrue(ModelPackageLoader.describe(pkg).contains("model.onnx"))
    }

    @Test
    fun `archivo onnx aislado busca tokenizer junto a el`() {
        val dir = tmp.newFolder("suelto")
        resourceDir().listFiles()?.forEach { it.copyTo(File(dir, it.name)) }
        val onnx = File(dir, "model.onnx")
        onnx.writeBytes(byteArrayOf(0x08, 0x05, 0x01, 0x00))

        val result = ModelPackageLoader.discover(onnx)

        assertTrue(result.isSuccess())
        assertEquals(dir, result.getOrNull()!!.dir)
        assertEquals("model.onnx", result.getOrNull()!!.onnxFile.name)
    }

    @Test
    fun `falta tokenizer_json produce error MISSING_REQUIRED_RESOURCE con mensaje util`() {
        val dir = tmp.newFolder("sin-tokenizer")
        File(dir, "model.onnx").writeBytes(byteArrayOf(0x08, 0x05, 0x01, 0x00))
        File(dir, "config.json").writeText("{}")

        val result = ModelPackageLoader.discover(dir)

        assertTrue("debe FALLAR sin tokenizer.json", result.isFailure())
        val f = result as VertilResult.Failure
        assertEquals("MISSING_REQUIRED_RESOURCE", f.code)
        assertTrue(
            "el error debe mencionar tokenizer.json",
            f.message.contains("tokenizer.json")
        )
        assertTrue(
            "el error debe explicar cómo completar el paquete",
            f.message.contains("auxiliares")
        )
    }

    @Test
    fun `directorio sin onnx produce error claro`() {
        val dir = tmp.newFolder("sin-onnx")
        File(dir, "tokenizer.json").writeText("{}")

        val result = ModelPackageLoader.discover(dir)

        assertTrue(result.isFailure())
        assertEquals("MISSING_REQUIRED_RESOURCE", (result as VertilResult.Failure).code)
    }

    @Test
    fun `ubicacion inexistente falla sin excepcion`() {
        val result = ModelPackageLoader.discover(File(tmp.root, "no-existe"))
        assertTrue(result.isFailure())
        assertEquals("LOCATION_NOT_FOUND", (result as VertilResult.Failure).code)
    }

    @Test
    fun `metadata_json se escribe con inventario y hashes`() {
        val dir = tmp.newFolder("pkg-meta")
        val file = File(dir, "model.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val out = ModelPackageLoader.writeMetadata(
            dir, "paquete-prueba",
            listOf(ModelPackageLoader.PackageFileEntry(file, "graph", 3, "abc123"))
        )

        val text = out.readText()
        assertTrue(text.contains("\"paquete-prueba\""))
        assertTrue(text.contains("model.onnx"))
        assertTrue(text.contains("abc123"))
        assertTrue(text.contains("graph"))
        // el metadata.json debe ser JSON válido
        kotlinx.serialization.json.Json.parseToJsonElement(text)
    }
}
