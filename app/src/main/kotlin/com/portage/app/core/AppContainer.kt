package com.portage.app.core

import android.app.Application
import android.content.Context
import com.portage.app.core.crypto.Vault
import com.portage.app.providers.AnthropicAdapter
import com.portage.app.providers.ChatEvent
import com.portage.app.providers.ChatRequest
import com.portage.app.providers.GoogleAdapter
import com.portage.app.providers.HttpProviderAdapter
import com.portage.app.providers.OpenAiCompatAdapter
import com.portage.app.providers.ProviderConfig
import com.portage.app.providers.ProviderKind
import com.portage.app.providers.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. One instance per process, created in
 * [PortageApp.onCreate]; hand-wired rather than Hilt-injected — the graph is
 * four objects deep and would cost more in build time than it returns.
 */
class AppContainer(context: Context) {

    val vault = Vault(context)
    val registry = ProviderRegistry(context)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Long-poll reads: a slow streaming response must not be cut off at 10s.
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val adapters: Map<ProviderKind, HttpProviderAdapter> = mapOf(
        ProviderKind.OPENAI to OpenAiCompatAdapter(httpClient, ProviderKind.OPENAI),
        ProviderKind.OLLAMA to OpenAiCompatAdapter(httpClient, ProviderKind.OLLAMA),
        ProviderKind.ANTHROPIC to AnthropicAdapter(httpClient),
        ProviderKind.GOOGLE to GoogleAdapter(httpClient),
    )

    /**
     * Resolves a provider config, fetches its secret from the vault, and
     * streams the response. Loading and vault access are part of the flow so
     * the caller gets a single stream that always ends in [ChatEvent.Done].
     */
    fun streamChat(configId: String, request: ChatRequest): Flow<ChatEvent> = flow {
        val config = registry.get(configId)
        if (config == null) {
            emit(ChatEvent.Failure("Provider \"$configId\" is not configured"))
            emit(ChatEvent.Done)
            return@flow
        }

        val apiKey = vault.get(config.apiKeyVaultId)
        if (apiKey.isNullOrBlank() && config.kind != ProviderKind.OLLAMA) {
            emit(ChatEvent.Failure("No API key stored for ${config.label}"))
            emit(ChatEvent.Done)
            return@flow
        }

        val adapter = adapters[config.kind]
        if (adapter == null) {
            emit(ChatEvent.Failure("No adapter for ${config.kind}"))
            emit(ChatEvent.Done)
            return@flow
        }

        emitAll(adapter.stream(config, apiKey, request))
    }
}

class PortageApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    companion object {
        fun containerOf(context: Context): AppContainer =
            (context.applicationContext as PortageApp).container
    }
}
