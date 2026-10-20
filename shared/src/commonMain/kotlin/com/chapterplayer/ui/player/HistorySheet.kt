package com.chapterplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import kotlin.time.Clock

/** Where the person jumped away from, newest first. A tap jumps back to that position and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>,
    onJump: (positionMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    // "Ago" is measured from when the sheet opened; reopening it refreshes the labels.
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "History",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).semantics { heading() }
            )
            if (history.isEmpty()) {
                Text(
                    text = "Nothing to jump back to yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(history, key = { i, e -> "history-$i-${e.recordedAt}-${e.positionMs}" }) { _, entry ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(onClickLabel = "Jump to this position", role = Role.Button) {
                                    onJump(entry.positionMs)
                                    onDismiss()
                                }
                                .padding(horizontal = 16.dp)
                                .semantics(mergeDescendants = true) { contentDescription = historyRowDescription(entry, now) },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = historyRowLabel(entry, now),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    item(key = "end-space") { Box(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
