package com.chapterplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.LocalTextClipboard
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings, About, Diagnostics (debug builds only): pick a book, read what the app and each server hold for it, and
 * copy or log the redacted report. Read only: nothing here changes data.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalTextClipboard.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(state.report) { copied = false }

    SettingsPage(title = "Diagnostics", onBack = onBack) {
        val report = state.report
        if (report == null) {
            Text(
                text = "Shows what this phone and each server hold for one book. Nothing here changes any data and no sign-in is refreshed or cleared; tokens, addresses and ids are left out.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search your books") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (state.choices.isEmpty()) {
                    Text("No book matches.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                }
                for (choice in state.choices) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { viewModel.select(choice.key) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(choice.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(choice.detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = viewModel::readFromServer, enabled = state.online && !state.reading) {
                    Text(if (state.reading) "Reading..." else "Read from server")
                }
                OutlinedButton(onClick = { clipboard.copy(report); copied = true }) { Text("Copy report") }
                OutlinedButton(onClick = viewModel::logReport) { Text("Log report") }
                OutlinedButton(onClick = viewModel::refreshLocal) { Text("Refresh phone data") }
                OutlinedButton(onClick = viewModel::clearSelection) { Text("Another book") }
            }
            val line = when {
                state.notice != null -> state.notice
                !state.online -> "The phone is offline, so the servers cannot be read."
                copied -> "Report copied."
                else -> null
            }
            if (line != null) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            }
            Text(
                text = "Logged under the tag ChapterPlayerDiag: adb logcat -s ChapterPlayerDiag",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceElevated), modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
