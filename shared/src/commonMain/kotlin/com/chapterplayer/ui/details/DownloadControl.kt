package com.chapterplayer.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/** What the download control's buttons do. */
class DownloadActions(
    val onDownload: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onCancel: () -> Unit,
    val onRemove: () -> Unit,
    val onRemoveConfirmed: () -> Unit,
    val onRemoveDismissed: () -> Unit,
)

/** Download, its progress with Pause / Resume / Cancel, or Remove download once it is on the phone. */
@Composable
internal fun DownloadControl(download: DetailsDownload, actions: DownloadActions) {
    if (download.phase == DetailsDownloadPhase.HIDDEN) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (download.phase) {
            DetailsDownloadPhase.IDLE -> {
                StartButton(download, actions.onDownload)
                DisabledReason(download.disabledReason)
            }
            DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING, DetailsDownloadPhase.PAUSED -> {
                Progress(download)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (download.phase == DetailsDownloadPhase.PAUSED) {
                        SmallAction("Resume", AmberGold, actions.onResume)
                    } else {
                        SmallAction("Pause", AmberGold, actions.onPause)
                    }
                    SmallAction("Cancel", TextSecondary, actions.onCancel)
                }
            }
            DetailsDownloadPhase.FAILED -> {
                StatusLine(download.statusText, MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StartButton(download, actions.onDownload, Modifier.weight(1f))
                    SmallAction("Cancel", TextSecondary, actions.onCancel)
                }
                DisabledReason(download.disabledReason)
            }
            DetailsDownloadPhase.DONE -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = download.statusText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = actions.onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Remove download", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            DetailsDownloadPhase.HIDDEN -> Unit
        }
    }
    if (download.confirmingRemove) {
        AlertDialog(
            onDismissRequest = actions.onRemoveDismissed,
            containerColor = SurfaceElevated,
            title = { Text("Remove download?") },
            text = { Text("The book's files are deleted from this phone. It stays on the server and in your library.") },
            confirmButton = {
                TextButton(onClick = actions.onRemoveConfirmed) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onRemoveDismissed) { Text("Keep", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun StartButton(download: DetailsDownload, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
        onClick = onClick,
        enabled = download.canStart,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberGold)
    ) {
        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(download.buttonLabel, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Progress(download: DetailsDownload) {
    LinearProgressIndicator(
        progress = { download.percent / 100f },
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = AmberGold,
        trackColor = SurfaceElevated
    )
    StatusLine(download.statusText, TextSecondary)
}

@Composable
private fun StatusLine(text: String?, color: Color) {
    if (text == null) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    )
}

@Composable
private fun SmallAction(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun DisabledReason(reason: String?) {
    if (reason == null) return
    Text(
        text = reason,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
}
