package com.vertil.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole { USER, ASSISTANT, SYSTEM }

@Serializable
data class ChatMessage(
    val id: Long = 0L,
    val timestamp: Long,
    val role: ChatRole,
    val content: String,
    val modelId: String? = null,
    val tokensGenerated: Int = 0,
    val durationMs: Long = 0L,
    val tokensPerSecond: Float? = null,
    val toolCalls: List<String> = emptyList(),
    val isError: Boolean = false
) {
    val isUser: Boolean get() = role == ChatRole.USER
    val isAssistant: Boolean get() = role == ChatRole.ASSISTANT
    val speedLabel: String?
        get() = if (tokensPerSecond != null && tokensPerSecond > 0) "%.1f tok/s".format(tokensPerSecond) else null
}

/**
 * Estado de la sesión de chat.
 */
data class ChatSessionState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val activeModelName: String? = null,
    val activeModelSize: String? = null
) {
    val hasMessages: Boolean get() = messages.isNotEmpty()
    val canSend: Boolean get() = !isGenerating
}
