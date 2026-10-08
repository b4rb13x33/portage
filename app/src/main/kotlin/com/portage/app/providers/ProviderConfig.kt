package com.portage.app.providers

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderKind { OPENAI, ANTHROPIC, GOOGLE, OLLAMA }

/**
 * A configured endpoint. Deliberately holds no secret — the API key lives in
 * [com.portage.app.core.crypto.Vault] under `provider:<id>`, and [apiKeyVaultId]
 * is the only link between the two.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val kind: ProviderKind,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String> = emptyList(),
) {
    val apiKeyVaultId: String get() = "provider:$id"

    companion object {
        /** Presets shown on first run. Base URLs are stable; model IDs are editable. */
        fun presets(): List<ProviderConfig> = listOf(
            ProviderConfig(
                id = "openai",
                kind = ProviderKind.OPENAI,
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o-mini",
                models = listOf("gpt-4o-mini", "gpt-4o"),
            ),
            ProviderConfig(
                id = "anthropic",
                kind = ProviderKind.ANTHROPIC,
                label = "Anthropic",
                baseUrl = "https://api.anthropic.com",
                defaultModel = "claude-3-5-haiku-latest",
                models = listOf("claude-3-5-haiku-latest", "claude-3-5-sonnet-latest"),
            ),
            ProviderConfig(
                id = "google",
                kind = ProviderKind.GOOGLE,
                label = "Google",
                baseUrl = "https://generativelanguage.googleapis.com",
                defaultModel = "gemini-2.0-flash",
                models = listOf("gemini-2.0-flash", "gemini-2.5-flash"),
            ),
            ProviderConfig(
                id = "ollama",
                kind = ProviderKind.OLLAMA,
                label = "Ollama (local)",
                baseUrl = "http://127.0.0.1:11434/v1",
                defaultModel = "qwen3fast",
                models = listOf("qwen3fast", "qwen3:4b"),
            ),
        )
    }
}
