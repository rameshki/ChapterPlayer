package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.LocalTextClipboard
import com.chapterplayer.ui.components.LocalTextSharer
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

internal const val BACKUP_TITLE = "ChapterPlayer backup"

/** Settings, Back up and restore: share a backup text, and paste one to put it back. */
@Composable
fun BackupSettingsScreen(onBack: () -> Unit, viewModel: BackupViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sharer = LocalTextSharer.current
    val clipboard = LocalTextClipboard.current
    var copied by remember { mutableStateOf(false) }
    BackupSettingsContent(
        state = state,
        copiedNote = copied,
        onExport = {
            copied = false
            viewModel.export { text ->
                if (sharer != null) sharer.share(BACKUP_TITLE, text) else { clipboard.copy(text); copied = true }
            }
        },
        onRestore = viewModel::restore,
        onBack = onBack,
    )
}

/** The page's body, apart from the view model so a screenshot test can draw it. */
@Composable
internal fun BackupSettingsContent(
    state: BackupUiState,
    copiedNote: Boolean,
    onExport: () -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pasting by remember { mutableStateOf(false) }
    SettingsPage(title = "Back up and restore", onBack = onBack) {
        SectionTitle("Back up")
        Text(
            "Makes a text with your favourites, Up next, corrections to merged books and these settings: theme, font, " +
                "layout, speed, skip lengths and a few more. Books are named by server address and id. It holds no " +
                "passwords or tokens, but your server addresses and book titles are in it: keep it to yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onExport,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) { Text("Share a backup") }
        if (copiedNote) {
            Text(
                "Backup copied. Paste it where you want to keep it.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        SectionTitle("Restore")
        Text(
            "Paste a backup made by ChapterPlayer. New favourites and Up next entries are added to yours; corrections " +
                "and settings in the backup replace the same ones here. Add your servers and let them sync first, so " +
                "the books can be found.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { pasting = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Paste a backup") }
        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
    }
    if (pasting) PasteDialog(onDismiss = { pasting = false }, onRestore = { text -> pasting = false; onRestore(text) })
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onRestore: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Backup text") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(text) }, enabled = text.isNotBlank()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
