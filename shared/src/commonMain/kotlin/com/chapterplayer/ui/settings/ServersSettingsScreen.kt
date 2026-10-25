package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Servers: each server with its connection and sync state, Sync now, Test connection (Plex), edit and delete. */
@Composable
fun ServersSettingsScreen(
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ServersViewModel = koinViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val test by viewModel.connectionTest.collectAsStateWithLifecycle()

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

        if (rows.isEmpty()) {
            Text(
                text = "No servers added yet. Tap + to connect to Audiobookshelf or Plex.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            rows.forEach { row ->
                Spacer(Modifier.height(8.dp))
                ServerRow(
                    row = row,
                    onSyncNow = { viewModel.syncNow(row.id) },
                    onTestConnection = { viewModel.testConnection(row.id) },
                    onEdit = { onEditServer(row.id) },
                    onDelete = { viewModel.deleteServer(row.id) }
                )
            }
        }
    }

    test?.let { ConnectionTestDialog(it, onDismiss = viewModel::dismissConnectionTest) }
}

@Composable
private fun ServerRow(
    row: ServerRowUi,
    onSyncNow: () -> Unit,
    onTestConnection: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = listOf(row.typeLabel, row.connectionLabel).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${row.name}", tint = AmberGold)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${row.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = AmberGold, strokeWidth = 2.dp)
                }
                Text(
                    text = row.statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            row.connectionNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp)
                )
            }
            if (row.needsSignIn) {
                Text(
                    text = "Sign in again",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (row.libraryId != null) {
                Text(
                    text = "Library: ${row.libraryId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Row {
                TextButton(onClick = onSyncNow, enabled = !row.syncing) {
                    Text("Sync now", color = if (row.syncing) TextSecondary else AmberGold)
                }
                if (row.canTestConnection) {
                    TextButton(onClick = onTestConnection) {
                        Text("Test connection", color = AmberGold)
                    }
                }
            }
        }
    }
}

/** Every connection of a Plex server, local / remote / relay, with OK or the reason it failed. Never a token. */
@Composable
private fun ConnectionTestDialog(test: ConnectionTestUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = {
            Text(
                text = "Connections of ${test.serverName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    test.running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AmberGold, strokeWidth = 2.dp)
                        Text("Trying each connection…", color = MaterialTheme.colorScheme.onSurface)
                    }
                    test.message != null -> Text(test.message, color = TextSecondary)
                }
                test.rows.forEach { r ->
                    Column {
                        Text(
                            text = r.kindLabel + if (r.inUse) " (in use)" else "",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(text = r.address, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(
                            text = r.result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (r.ok) AmberGold else MaterialTheme.colorScheme.error
                        )
                    }
                }
                test.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = AmberGold) }
        }
    )
}
