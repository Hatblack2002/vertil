package com.vertil.model.runtime

import com.vertil.core.VertilResult
import com.vertil.model.GenerationParams
import com.vertil.model.GenerationResult
import com.vertil.model.LocalModel
import com.vertil.model.ModelCapabilities
import com.vertil.model.ModelInfo
import com.vertil.model.ModelState

/**
 * Runtime GGUF.
 *
 * Estado: NO IMPLEMENTADO (stub documentado).
 *
 * Para integrar GGUF se requiere llama.cpp compilado para Android via NDK + JNI.
 * No es viable incluirlo en v1.0 sin romper estabilidad. La interfaz existe para
 * que la integración futura no rompa el resto del sistema.
 *
 * Cuando se integre, esta clase se sustituye por una implementación real sin
 * tocar ModelManager, ChatController ni UI.
 */
class GgufModelRuntime : LocalModel {

    override val runtimeId: String = "gguf"
    override val info: ModelInfo? = null
    override val state: ModelState = ModelState.INSTALLED
    override val capabilities: ModelCapabilities = ModelCapabilities()

    override suspend fun load(info: ModelInfo): VertilResult<ModelInfo> =
        VertilResult.fail(
            message = "Runtime GGUF no implementado en v1.0. Próximamente vía llama.cpp + JNI.",
            module = "GgufRuntime",
            code = "NOT_IMPLEMENTED"
        )

    override suspend fun unload(): VertilResult<Unit> = VertilResult.ok(Unit)
    override suspend fun generate(params: GenerationParams): VertilResult<GenerationResult> =
        VertilResult.fail("GGUF no implementado", module = "GgufRuntime", code = "NOT_IMPLEMENTED")

    override fun cancel() = Unit
    override fun getRuntimeInfo(): Map<String, String> = mapOf(
        "Runtime" to "GGUF (NO IMPLEMENTADO)",
        "Estado" to "Pendiente v1.1 — requiere NDK + JNI llama.cpp"
    )
}
