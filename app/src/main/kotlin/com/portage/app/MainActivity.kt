package com.portage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portage.app.ui.chat.ChatScreen
import com.portage.app.ui.chat.ChatViewModel
import com.portage.app.ui.providers.ProvidersScreen
import com.portage.app.ui.providers.ProvidersViewModel
import com.portage.app.ui.theme.PortageTheme

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Chat", Icons.AutoMirrored.Filled.Send),
    Tab("Providers", Icons.Default.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PortageTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen(
    chatViewModel: ChatViewModel = viewModel(),
    providersViewModel: ProvidersViewModel = viewModel(),
) {
    val chatState by chatViewModel.state.collectAsState()
    val providersState by providersViewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tabSpec ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(tabSpec.icon, contentDescription = tabSpec.label) },
                        label = { Text(tabSpec.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> ChatScreen(
                    state = chatState,
                    onInputChanged = chatViewModel::onInputChanged,
                    onSend = chatViewModel::send,
                    onStop = chatViewModel::stop,
                    onClear = chatViewModel::clear,
                    onProviderSelected = chatViewModel::selectProvider,
                    onModelChanged = chatViewModel::onModelChanged,
                    onSystemPromptChanged = chatViewModel::onSystemPromptChanged,
                )

                else -> ProvidersScreen(
                    state = providersState,
                    onSaveKey = providersViewModel::saveKey,
                    onClearKey = providersViewModel::clearKey,
                    onDeleteProvider = providersViewModel::deleteProvider,
                    onDismissMessage = providersViewModel::dismissMessage,
                )
            }
        }
    }
}
