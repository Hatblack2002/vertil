package com.vertil.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFormatTest {

    @Test
    fun `fromExtension detects ONNX`() {
        assertEquals(ModelFormat.ONNX, ModelFormat.fromExtension("model.onnx"))
        assertEquals(ModelFormat.ONNX, ModelFormat.fromExtension("MODEL.ONNX"))
    }

    @Test
    fun `fromExtension detects GGUF`() {
        assertEquals(ModelFormat.GGUF, ModelFormat.fromExtension("llama.gguf"))
    }

    @Test
    fun `fromExtension detects TFLite`() {
        assertEquals(ModelFormat.TFLITE, ModelFormat.fromExtension("model.tflite"))
        assertEquals(ModelFormat.TFLITE, ModelFormat.fromExtension("model.lite"))
    }

    @Test
    fun `fromExtension returns UNKNOWN for unsupported`() {
        assertEquals(ModelFormat.UNKNOWN, ModelFormat.fromExtension("model.bin"))
        assertEquals(ModelFormat.UNKNOWN, ModelFormat.fromExtension("file.txt"))
    }
}

class ModelInfoTest {

    @Test
    fun `humanSize formats correctly`() {
        assertEquals("0 B", ModelInfo.humanSize(0))
        assertEquals("512 B", ModelInfo.humanSize(512))
        assertEquals("1 KB", ModelInfo.humanSize(1024))
        assertTrue(ModelInfo.humanSize(1024 * 1024).contains("MB"))
        assertTrue(ModelInfo.humanSize(1024L * 1024 * 1024).contains("GB"))
    }
}
