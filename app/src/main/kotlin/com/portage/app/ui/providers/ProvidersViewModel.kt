package com.portage.app.ui.providers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.portage.app.core.PortageApp
import com.portage.app.providers.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProvidersState(
    val providers: List<ProviderConfig> = emptyList(),
    val keysPresent: Set<String> = emptySet(),
    val message: String? = null,
)

class ProvidersViewModel(application: Application) : AndroidViewModel(application) {

    private val container = PortageApp.containerOf(application)

    private val _state = MutableStateFlow(ProvidersState())
    val state: StateFlow<ProvidersState> = _state.asStateFlow()

    init {
        viewModelScope.launch { container.registry.seedIfEmpty() }
        viewModelScope.launch {
            container.registry.providers.collect { list ->
                val present = list.filter { container.vault.has(it.apiKeyVaultId) }
                    .map { it.id }
                    .toSet()
                _state.update { it.copy(providers = list, keysPresent = present) }
            }
        }
    }

    fun saveKey(config: ProviderConfig, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            container.vault.put(config.apiKeyVaultId, trimmed)
            _state.update { it.copy(keysPresent = it.keysPresent + config.id, message = "Key saved for ${config.label}") }
        }
    }

    fun clearKey(config: ProviderConfig) {
        viewModelScope.launch {
            container.vault.delete(config.apiKeyVaultId)
            _state.update { it.copy(keysPresent = it.keysPresent - config.id, message = "Key removed for ${config.label}") }
        }
    }

    fun addProvider(config: ProviderConfig) {
        viewModelScope.launch {
            container.registry.upsert(config)
            _state.update { it.copy(message = "Saved ${config.label}") }
        }
    }

    fun deleteProvider(config: ProviderConfig) {
        if (config.id in BUILTIN_IDS) {
            _state.update { it.copy(message = "${config.label} is built in and cannot be removed") }
            return
        }
        viewModelScope.launch {
            container.registry.delete(config.id)
            container.vault.delete(config.apiKeyVaultId)
            _state.update { it.copy(message = "Removed ${config.label}") }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private companion object {
        val BUILTIN_IDS = setOf("openai", "anthropic", "google", "ollama")
    }
}
