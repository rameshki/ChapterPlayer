package com.chapterplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.library.CoverArt
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

const val UP_NEXT_EMPTY_TEXT = "Nothing in Up next. Long-press a book and choose Play next or Add to Up next."

/** The Up next sheet of the full player, on its own view model. */
@Composable
fun UpNextSheet(onDismiss: () -> Unit, viewModel: UpNextViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UpNextSheetContent(
        state = state,
        onRemove = viewModel::onRemove,
        onMoveUp = viewModel::onMoveUp,
        onMoveDown = viewModel::onMoveDown,
        onClearAll = viewModel::onClearAll,
        onDismiss = onDismiss,
    )
}

/** The books that play after this one, in order: cover, title, author, length; move up / down, remove, clear all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheetContent(
    state: UpNextUiState,
    onRemove: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Up next",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (state.rows.isNotEmpty() && !confirmingClear) {
                    TextButton(onClick = { confirmingClear = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Clear all", color = AmberGold)
                    }
                }
            }
            if (confirmingClear && state.rows.isNotEmpty()) {
                // Clearing asks once more: one stray tap does not throw the queue away.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { confirmingClear = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Keep", color = TextSecondary)
                    }
                    TextButton(
                        onClick = {
                            confirmingClear = false
                            onClearAll()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Clear Up next", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (state.rows.isEmpty()) {
                Text(
                    text = if (state.loading) "" else UP_NEXT_EMPTY_TEXT,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.rows, key = { it.queueId }) { row -> UpNextRowItem(row, onRemove, onMoveUp, onMoveDown) }
                    item(key = "end-space") { Box(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UpNextRowItem(row: UpNextRow, onRemove: (String) -> Unit, onMoveUp: (String) -> Unit, onMoveDown: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) { CoverArt(row.book, Modifier.size(48.dp)) }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = row.book.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val line = listOf(row.book.author, row.lengthText).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { onMoveUp(row.queueId) }, enabled = row.canMoveUp, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${row.book.title} up")
        }
        IconButton(onClick = { onMoveDown(row.queueId) }, enabled = row.canMoveDown, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${row.book.title} down")
        }
        IconButton(onClick = { onRemove(row.queueId) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove ${row.book.title} from Up next", tint = TextSecondary)
        }
    }
}
