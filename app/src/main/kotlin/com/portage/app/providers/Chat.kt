package com.portage.app.providers

import kotlinx.serialization.Serializable

@Serializable
enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String,
)

/**
 * Provider-neutral chat request.
 *
 * `system` is carried separately from [messages] because every provider places
 * it somewhere different: a leading message for OpenAI, a top-level field for
 * Anthropic, `systemInstruction` for Google. Adapters decide; callers do not.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val system: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
) {
    init {
        require(messages.isNotEmpty()) { "request must contain at least one message" }
        require(messages.none { it.role == Role.SYSTEM }) {
            "system prompt belongs in ChatRequest.system, not in messages"
        }
    }
}

sealed interface ChatEvent {
    /** Incremental assistant text. Concatenate in order. */
    data class TextDelta(val text: String) : ChatEvent

    /**
     * Incremental chain-of-thought. Thinking models (qwen3, DeepSeek-R, o-series)
     * stream this in a separate field; without it the UI shows nothing while the
     * model works, which reads as a hang. Never mixed into [TextDelta].
     */
    data class ReasoningDelta(val text: String) : ChatEvent

    /** Token accounting, emitted at most once and only if the provider reports it. */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : ChatEvent

    /** Terminal event. Always emitted, including after [Failure]. */
    data object Done : ChatEvent

    data class Failure(val message: String) : ChatEvent
}
