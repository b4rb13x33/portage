package com.portage.app.providers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.portage.app.core.json.networkJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.providerStore: DataStore<Preferences> by preferencesDataStore(name = "providers")

/**
 * Persists [ProviderConfig] definitions. Secrets are never written here —
 * they go to the Vault, keyed by [ProviderConfig.apiKeyVaultId].
 */
class ProviderRegistry(private val context: Context) {

    private val store get() = context.providerStore

    val providers: Flow<List<ProviderConfig>> = store.data
        .map { prefs -> decode(prefs[KEY]) }
        .distinctUntilChanged()

    suspend fun get(id: String): ProviderConfig? =
        providers.first().firstOrNull { it.id == id }

    suspend fun upsert(config: ProviderConfig) {
        val current = providers.first().filterNot { it.id == config.id }
        write(current + config)
    }

    suspend fun delete(id: String) {
        write(providers.first().filterNot { it.id == id })
    }

    /** Writes the built-in presets on first launch, leaving user edits alone. */
    suspend fun seedIfEmpty() {
        if (providers.first().isEmpty()) write(ProviderConfig.presets())
    }

    private suspend fun write(configs: List<ProviderConfig>) {
        store.edit { it[KEY] = networkJson.encodeToString(configs) }
    }

    private fun decode(raw: String?): List<ProviderConfig> =
        if (raw == null) emptyList()
        else runCatching { networkJson.decodeFromString<List<ProviderConfig>>(raw) }
            .getOrDefault(emptyList())

    private companion object {
        val KEY = stringPreferencesKey("config:list")
    }
}
