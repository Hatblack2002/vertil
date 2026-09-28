package com.vertil.model.runtime.engine

import com.vertil.core.log.VertilLog
import com.vertil.tokenizer.ChatTemplate
import com.vertil.tokenizer.HfConfigs
import com.vertil.tokenizer.HfTokenizer

/**
 * Turno de conversación genérico (rol + contenido). Desacopla el motor del
 * modelo de mensajes de la UI (com.vertil.chat.ChatMessage).
 */
data class ChatTurn(
    val role: String,       // "system" | "user" | "assistant"
    val content: String
)

/**
 * Construcción del prompt conversacional (especificación §6):
 *  - template REAL desde `chat_template` de tokenizer_config.json;
 *  - subconjunto Jinja2 documentado en [ChatTemplate]; si el template usa
 *    construcciones no soportadas → TemplateException explícita, NUNCA un
 *    prompt incorrecto silencioso;
 *  - fallback genérico ChatML solo si el tokenizer no declara template o el
 *    render falla, usando los tokens <|im_start|>/<|im_end|> del tokenizer real
 *    (si el vocabulario no los tiene, formato plano "role: content").
 */
object ChatTemplateEngine {

    data class PromptBuild(
        val prompt: String,
        /** "template" (chat_template del modelo) | "fallback" (ChatML genérico). */
        val source: String
    )

    fun buildPrompt(
        messages: List<ChatTurn>,
        tokenizerConfig: HfConfigs.TokenizerConfig?,
        tokenizer: HfTokenizer
    ): PromptBuild {
        val ctx: Map<String, Any?> = mapOf(
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
            "add_generation_prompt" to true,
            "bos_token" to (tokenizerConfig?.bosToken ?: "<|im_start|>"),
            "eos_token" to (tokenizerConfig?.eosToken ?: "<|im_end|>"),
            "pad_token" to (tokenizerConfig?.padToken ?: "")
        )
        val fb = ChatTemplate.FallbackTokens(
            imStart = "<|im_start|>",
            imEnd = "<|im_end|>",
            hasImTokens = tokenizer.tokenToId("<|im_start|>") != null &&
                tokenizer.tokenToId("<|im_end|>") != null
        )
        val (prompt, source) = ChatTemplate.renderOrFallback(
            tokenizerConfig?.chatTemplate, ctx, fb
        )
        if (source == "fallback") {
            VertilLog.w(
                TAG,
                "Chat template del modelo ausente o no renderizable; usando ChatML genérico " +
                    "(hasImTokens=${fb.hasImTokens})"
            )
        }
        return PromptBuild(prompt, source)
    }

    private const val TAG = "ChatTemplate"
}
