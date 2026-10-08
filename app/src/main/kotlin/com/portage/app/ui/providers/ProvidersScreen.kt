package com.portage.app.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.portage.app.providers.ProviderConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    state: ProvidersState,
    onSaveKey: (ProviderConfig, String) -> Unit,
    onClearKey: (ProviderConfig) -> Unit,
    onDeleteProvider: (ProviderConfig) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onDismissMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Providers") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) {
            items(state.providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    hasKey = provider.id in state.keysPresent,
                    onSaveKey = { key -> onSaveKey(provider, key) },
                    onClearKey = { onClearKey(provider) },
                    onDelete = { onDeleteProvider(provider) },
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    hasKey: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onDelete: () -> Unit,
) {
    var keyInput by remember(provider.id) { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(provider.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        provider.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (provider.kind == com.portage.app.providers.ProviderKind.OLLAMA) {
                        "Local · no key"
                    } else if (hasKey) {
                        "Key saved"
                    } else {
                        "No key"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasKey || provider.kind == com.portage.app.providers.ProviderKind.OLLAMA) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (provider.id !in setOf("openai", "anthropic", "google", "ollama")) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete provider")
                    }
                }
            }

            if (provider.kind != com.portage.app.providers.ProviderKind.OLLAMA) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(if (hasKey) "Replace API key" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            onSaveKey(keyInput)
                            keyInput = ""
                        },
                        enabled = keyInput.isNotBlank(),
                    ) {
                        Text("Save key")
                    }
                    if (hasKey) {
                        TextButton(onClick = onClearKey) { Text("Remove") }
                    }
                }
            }
        }
    }
}
