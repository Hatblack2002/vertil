package com.vertil.model

import com.vertil.model.runtime.GgufModelRuntime
import com.vertil.model.runtime.MockModelRuntime
import com.vertil.model.runtime.OnnxModelRuntime
import com.vertil.model.runtime.TfliteModelRuntime

/**
 * Registro estático de runtimes disponibles.
 *
 * Para añadir un runtime nuevo: implementar [LocalModel] y añadirlo aquí.
 * El resto del sistema no necesita cambios.
 */
object ModelRuntimeRegistry {

    val all: List<LocalModel> = listOf(
        OnnxModelRuntime(),
        GgufModelRuntime(),
        TfliteModelRuntime(),
        MockModelRuntime()
    )

    /** Runtime mock singleton — se usa cuando no hay modelo real. */
    val mock: MockModelRuntime = all.first { it.runtimeId == "mock" } as MockModelRuntime

    fun byId(id: String): LocalModel? = all.firstOrNull { it.runtimeId == id }

    fun forFormat(format: ModelFormat): LocalModel = when (format) {
        ModelFormat.ONNX -> byId("onnx")!!
        ModelFormat.GGUF -> byId("gguf")!!
        ModelFormat.TFLITE -> byId("tflite")!!
        ModelFormat.UNKNOWN -> mock
    }
}
