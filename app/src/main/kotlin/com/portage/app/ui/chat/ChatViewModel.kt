package com.portage.app.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portage.app.core.PortageApp
import com.portage.app.providers.ChatEvent
import com.portage.app.providers.ChatMessage
import com.portage.app.providers.ChatRequest
import com.portage.app.providers.ProviderConfig
import com.portage.app.providers.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(
    val role: Role,
    val content: String,
    val streaming: Boolean = false,
    val error: Boolean = false,
    /** Chain-of-thought streamed before the answer; empty for non-thinking models. */
    val reasoning: String = "",
)

data class ChatState(
    val providers: List<ProviderConfig> = emptyList(),
    val selectedProviderId: String? = null,
    val model: String = "",
    val systemPrompt: String = "",
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val status: String? = null,
    val usage: ChatEvent.Usage? = null,
    val keysPresent: Set<String> = emptySet(),
) {
    val selectedProvider: ProviderConfig?
        get() = providers.firstOrNull { it.id == selectedProviderId }

    fun withAssistant(
        index: Int,
        content: String,
        streaming: Boolean,
        reasoning: String = messages.getOrNull(index)?.reasoning.orEmpty(),
    ): ChatState =
        copy(messages = messages.mapIndexed { i, m ->
            if (i == index) m.copy(content = content, streaming = streaming, reasoning = reasoning) else m
        })
}

/**
 * M1 chat: multi-turn, in-memory history, streaming tokens.
 *
 * History persistence arrives with Room in M2; keeping it in memory here
 * keeps the streaming path free of database writes on the hot loop.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = PortageApp.containerOf(application)

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch { container.registry.seedIfEmpty() }
        viewModelScope.launch {
            container.registry.providers.collect { list ->
                // Local Ollama needs no key, so it is always considered ready.
                val present = list.filter {
                    it.kind == com.portage.app.providers.ProviderKind.OLLAMA ||
                        container.vault.has(it.apiKeyVaultId)
                }.map { it.id }.toSet()
                _state.update { s ->
                    val selected = s.selectedProviderId
                        ?.takeIf { id -> list.any { it.id == id } }
                        ?: list.firstOrNull()?.id
                    s.copy(
                        providers = list,
                        selectedProviderId = selected,
                        keysPresent = present,
                        model = s.model.ifBlank {
                            list.firstOrNull { it.id == selected }?.defaultModel.orEmpty()
                        },
                    )
                }
            }
        }
    }

    fun onInputChanged(value: String) = _state.update { it.copy(input = value) }

    fun onModelChanged(value: String) = _state.update { it.copy(model = value) }

    fun onSystemPromptChanged(value: String) = _state.update { it.copy(systemPrompt = value) }

    fun selectProvider(id: String) {
        _state.update { s ->
            val provider = s.providers.firstOrNull { it.id == id }
            s.copy(
                selectedProviderId = id,
                model = provider?.defaultModel ?: s.model,
            )
        }
    }

    fun send() {
        val s = _state.value
        val text = s.input.trim()
        val providerId = s.selectedProviderId ?: return
        if (text.isEmpty() || s.sending) return

        val model = s.model.trim().ifEmpty { s.selectedProvider?.defaultModel ?: return }

        val request = ChatRequest(
            model = model,
            // Drop failed and blank turns: replaying them corrupts the next request.
            messages = s.messages
                .filter { !it.error && it.content.isNotBlank() }
                .map { ChatMessage(it.role, it.content) } + ChatMessage(Role.USER, text),
            system = s.systemPrompt.trim().takeIf { it.isNotBlank() },
        )

        _state.update {
            it.copy(
                input = "",
                sending = true,
                status = null,
                usage = null,
                messages = it.messages +
                    UiMessage(Role.USER, text) +
                    UiMessage(Role.ASSISTANT, content = "", streaming = true),
            )
        }
        val assistantIndex = _state.value.messages.lastIndex

        streamJob = viewModelScope.launch {
            val draft = StringBuilder()
            val reasoning = StringBuilder()
            var failed = false

            container.streamChat(providerId, request).collect { event ->
                when (event) {
                    is ChatEvent.TextDelta -> {
                        draft.append(event.text)
                        _state.update { it.withAssistant(assistantIndex, draft.toString(), streaming = true) }
                    }

                    is ChatEvent.ReasoningDelta -> {
                        reasoning.append(event.text)
                        _state.update { it.withAssistant(assistantIndex, draft.toString(), streaming = true, reasoning = reasoning.toString()) }
                    }

                    is ChatEvent.Usage -> _state.update { it.copy(usage = event) }

                    is ChatEvent.Failure -> {
                        failed = true
                        _state.update { st ->
                            st.copy(messages = st.messages.mapIndexed { i, m ->
                                if (i == assistantIndex) {
                                    m.copy(content = event.message, streaming = false, error = true)
                                } else {
                                    m
                                }
                            })
                        }
                    }

                    is ChatEvent.Done -> _state.update { st ->
                        val content = st.messages.getOrNull(assistantIndex)?.content.orEmpty()
                        st.withAssistant(assistantIndex, content, streaming = false)
                            .copy(
                                sending = false,
                                status = when {
                                    failed -> null
                                    content.isBlank() -> "No response"
                                    else -> null
                                },
                            )
                    }
                }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.update { s ->
            val idx = s.messages.indexOfLast { it.streaming }
            val next = if (idx >= 0) s.withAssistant(idx, s.messages[idx].content, streaming = false) else s
            next.copy(sending = false, status = "Stopped")
        }
    }

    fun clear() {
        streamJob?.cancel()
        _state.update {
            it.copy(messages = emptyList(), status = null, usage = null)
        }
    }
}
