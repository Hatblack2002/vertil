package com.vertil.model.runtime

import com.vertil.core.VertilResult
import com.vertil.core.log.VertilLog
import com.vertil.model.GenerationParams
import com.vertil.model.GenerationResult
import com.vertil.model.LocalModel
import com.vertil.model.ModelCapabilities
import com.vertil.model.ModelFormat
import com.vertil.model.ModelInfo
import com.vertil.model.ModelState
import kotlinx.coroutines.delay

/**
 * Runtime MOCK.
 *
 * Estado: IMPLEMENTADO (claramente marcado como MOCK, no fake).
 *
 * Se usa cuando no hay modelo real cargado. Permite que toda la infraestructura
 * de VERTIL (chat, herramientas, permisos, registro) funcione end-to-end sin
 * modelo local. Las respuestas son obvias y nunca pretenden ser inferencia real.
 *
 * NO es una demo de IA: es un runtime sin modelo.
 */
class MockModelRuntime : LocalModel {

    override val runtimeId: String = "mock"
    override var info: ModelInfo? = null
        private set
    override var state: ModelState = ModelState.INSTALLED
        private set
    override val capabilities: ModelCapabilities = ModelCapabilities(
        supportsChat = true,
        supportsStreaming = false,
        supportsToolCalling = false,
        maxContextLength = 512,
        maxOutputTokens = 256
    )

    @Volatile private var generating = false

    override suspend fun load(info: ModelInfo): VertilResult<ModelInfo> {
        VertilLog.w("MockRuntime", "load() — MOCK runtime activo (no hay modelo real)")
        state = ModelState.LOADING
        delay(150) // simular tiempo de carga mínimo
        val newInfo = info.copy(state = ModelState.READY, format = ModelFormat.UNKNOWN)
        this.info = newInfo
        state = ModelState.READY
        return VertilResult.ok(newInfo)
    }

    override suspend fun unload(): VertilResult<Unit> {
        info = null
        state = ModelState.INSTALLED
        return VertilResult.ok(Unit)
    }

    override suspend fun generate(params: GenerationParams): VertilResult<GenerationResult> {
        if (state != ModelState.READY) {
            return VertilResult.fail("Mock runtime not ready", module = "MockRuntime")
        }
        generating = true
        state = ModelState.GENERATING
        val start = System.currentTimeMillis()
        delay(200) // no medimos tokens reales
        val elapsed = System.currentTimeMillis() - start
        generating = false
        state = ModelState.READY

        // Respuesta clara y honesta: NO es inferencia.
        val text = buildString {
            appendLine("[VERTIL · sin modelo local cargado]")
            appendLine()
            appendLine("No tengo un modelo local activo, por lo que no puedo generar texto con inferencia real.")
            appendLine("Para activar la inferencia local:")
            appendLine("1. Ve a la pestaña «Modelos».")
            appendLine("2. Importa un modelo ONNX.")
            appendLine("3. Cárgalo y vuelve aquí.")
            appendLine()
            appendLine("Mientras tanto, puedo seguir ejecutando herramientas del dispositivo (búsqueda de archivos, hash, info del dispositivo, etc.) si me lo pides explícitamente.")
        }

        return VertilResult.ok(
            GenerationResult(
                text = text,
                tokensGenerated = 0,
                durationMs = elapsed,
                tokensPerSecond = null
            )
        )
    }

    override fun cancel() {
        generating = false
        if (state == ModelState.GENERATING) state = ModelState.READY
    }

    override fun getRuntimeInfo(): Map<String, String> = mapOf(
        "Runtime" to "Mock (sin modelo)",
        "Format" to "—",
        "Quantization" to "—",
        "Context" to "—",
        "Threads" to "—",
        "RAM" to "—"
    )
}
