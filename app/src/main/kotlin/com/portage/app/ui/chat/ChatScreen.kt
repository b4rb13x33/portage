package com.portage.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.portage.app.providers.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onProviderSelected: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onSystemPromptChanged: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    var providerMenu by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Portage") },
                actions = {
                    Box {
                        TextButton(onClick = { providerMenu = true }) {
                            Text(state.selectedProvider?.label ?: "Provider")
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            state.providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(provider.label)
                                            if (provider.id in state.keysPresent) {
                                                Text("  •", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    },
                                    onClick = {
                                        onProviderSelected(provider.id)
                                        providerMenu = false
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { showSystem = !showSystem }) {
                        Text(if (showSystem) "Hide system" else "System")
                    }
                    TextButton(onClick = onClear) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (showSystem) {
                OutlinedTextField(
                    value = state.systemPrompt,
                    onValueChange = onSystemPromptChanged,
                    label = { Text("System prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    minLines = 2,
                    maxLines = 5,
                )
                HorizontalDivider()
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = onModelChanged,
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.messages.isEmpty()) {
                EmptyState(
                    providerLabel = state.selectedProvider?.label,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    itemsIndexed(state.messages) { _, message ->
                        MessageBubble(message)
                    }

                    if (state.sending) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.padding(end = 12.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    "Thinking…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            state.status?.let { status ->
                Text(
                    status,
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.usage?.let { usage ->
                Text(
                    "${usage.inputTokens} in · ${usage.outputTokens} out",
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Message") },
                minLines = 1,
                maxLines = 6,
                trailingIcon = {
                    if (state.sending) {
                        TextButton(onClick = onStop) { Text("Stop") }
                    }
                },
            )

            Button(
                onClick = if (state.sending) onStop else onSend,
                enabled = state.sending || state.input.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Text(if (state.sending) "Stop" else "Send")
            }
        }
    }
}

@Composable
private fun EmptyState(providerLabel: String?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            if (providerLabel == null) {
                "Add an API key in Providers, then start chatting."
            } else {
                "Using $providerLabel. Send a message to begin."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val fromUser = message.role == Role.USER
    val background = when {
        fromUser -> MaterialTheme.colorScheme.primaryContainer
        message.error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
        message.error -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .background(background, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.reasoning.isNotEmpty()) {
                Text(
                    message.reasoning + if (message.streaming && message.content.isEmpty()) " ▌" else "",
                    color = textColor.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                )
            }

            if (message.content.isEmpty() && message.streaming) {
                Text(
                    if (message.reasoning.isEmpty()) "…" else "",
                    color = textColor,
                    fontStyle = FontStyle.Italic,
                )
            } else if (message.content.isNotEmpty() || !message.streaming) {
                Text(
                    message.content + if (message.streaming && message.content.isNotEmpty()) " ▌" else "",
                    color = textColor,
                )
            }
        }
    }
}
