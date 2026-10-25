package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Servers: the connected servers, with edit, delete and the add button. */
@Composable
fun ServersSettingsScreen(
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.settingsUiState.collectAsStateWithLifecycle()

    SettingsPage(
        title = "Servers",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add server")
            }
        },
        bottomPadding = 88.dp
    ) {
        SectionTitle("Connected Servers")

        if (uiState.servers.isEmpty()) {
            Text(
                text = "No servers added yet. Tap + to connect to Audiobookshelf or Plex.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            uiState.servers.forEach { server ->
                Spacer(Modifier.height(8.dp))
                ServerRow(
                    server = server,
                    onEdit = { onEditServer(server.id) },
                    onDelete = { viewModel.deleteServer(server.id) }
                )
            }
        }
    }
}
