package com.chapterplayer.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/** What the details page does about the copies of a book several servers have. */
class SourceActions(
    val onSourceChosen: (bookId: String) -> Unit,
    val onSourceSheetDismissed: () -> Unit,
    val onNotSameBook: () -> Unit,
    val onMergeWith: () -> Unit,
    val onMergeQueryChanged: (String) -> Unit,
    val onMergeCandidateChosen: (bookId: String) -> Unit,
    val onMergeSheetDismissed: () -> Unit,
)

private fun rowText(row: SourceRow): String = if (row.serverName.isBlank()) row.typeLabel else "${row.typeLabel} · ${row.serverName}"

private fun rowDetail(row: SourceRow): String? = when {
    row.downloaded -> "Downloaded"
    row.reason != null -> row.reason
    else -> row.sizeText
}

/** The Sources section of a merged book: each copy with its type, server and state. */
@Composable
internal fun SourcesSection(rows: List<SourceRow>, description: String?) {
    if (rows.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = "Sources",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        description?.let {
            Text(text = it.replaceFirstChar { c -> c.uppercaseChar() }, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rowText(row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                rowDetail(row)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = if (row.downloaded) AmberGold else TextSecondary)
                }
            }
        }
    }
}

/** "Play from" / "Download from": one row per copy, downloaded first; a copy that cannot be used is greyed with the reason. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSheet(sheet: SourceSheetUi, actions: SourceActions) {
    ModalBottomSheet(onDismissRequest = actions.onSourceSheetDismissed, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = sheet.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }
            )
            sheet.rows.forEach { row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .clickable(enabled = row.enabled, role = Role.Button) { actions.onSourceChosen(row.bookId) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = rowText(row),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (row.enabled) MaterialTheme.colorScheme.onSurface else TextSecondary.copy(alpha = 0.6f)
                    )
                    rowDetail(row)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = if (row.downloaded) AmberGold else TextSecondary)
                    }
                }
            }
        }
    }
}

/** The ⋮ of the details page: "Not the same book" on a merged book, "Merge with…" on any book. */
@Composable
internal fun DetailsOverflowMenu(canSplit: Boolean, canMerge: Boolean, actions: SourceActions) {
    if (!canSplit && !canMerge) return
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.size(48.dp)) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SurfaceElevated) {
            if (canSplit) {
                DropdownMenuItem(
                    text = { Text("Not the same book", style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; actions.onNotSameBook() }
                )
            }
            if (canMerge) {
                DropdownMenuItem(
                    text = { Text("Merge with…", style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; actions.onMergeWith() }
                )
            }
        }
    }
}

/** "Merge with…": search the books of the other servers and pick the one that is the same book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MergeWithSheet(sheet: MergeSheetUi, actions: SourceActions) {
    ModalBottomSheet(onDismissRequest = actions.onMergeSheetDismissed, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = "Merge with…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() }
            )
            Text(
                text = "Pick the same book on another server. They show as one book from then on.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            OutlinedTextField(
                value = sheet.query,
                onValueChange = actions.onMergeQueryChanged,
                singleLine = true,
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            if (sheet.candidates.isEmpty()) {
                Text(
                    text = "No book on another server matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(sheet.candidates, key = { it.bookId }) { candidate ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .clickable(role = Role.Button) { actions.onMergeCandidateChosen(candidate.bookId) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = candidate.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOf(candidate.author, candidate.typeLabel, candidate.serverName).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
