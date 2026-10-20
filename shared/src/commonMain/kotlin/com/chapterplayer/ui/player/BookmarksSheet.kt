package com.chapterplayer.ui.player

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.defaultBookmarkTitle
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.FieldBorder
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/**
 * The playing book's bookmarks. Add saves the position of the tap; a row tap jumps and closes the sheet;
 * the row menu renames or deletes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    currentPositionMs: Long,
    onAdd: (name: String?, onDone: (saved: Boolean) -> Unit) -> Unit,
    onRename: (bookId: String, timeSec: Long, name: String) -> Unit,
    onDelete: (bookId: String, timeSec: Long) -> Unit,
    onJump: (positionMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).semantics { heading() }
            )
            AddBookmarkRow(hint = defaultBookmarkTitle(currentPositionMs), onAdd = onAdd)
            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(bookmarks, key = { "bookmark-${it.timeSec}" }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onClick = {
                                onJump(bookmark.timeSec * 1000L)
                                onDismiss()
                            },
                            onRename = { renaming = bookmark },
                            onDelete = { onDelete(bookmark.bookId, bookmark.timeSec) }
                        )
                    }
                    item(key = "end-space") { Box(Modifier.height(24.dp)) }
                }
            }
        }
    }
    renaming?.let { target ->
        RenameBookmarkDialog(
            initial = target.title,
            onConfirm = { name ->
                onRename(target.bookId, target.timeSec, name)
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun AddBookmarkRow(hint: String, onAdd: (String?, (Boolean) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val submit = {
        val typed = name
        // The field is cleared once the bookmark is saved; if saving fails the typed name stays for another try.
        onAdd(typed.ifBlank { null }) { saved -> if (saved && name == typed) name = "" }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name (optional)", color = TextSecondary) },
            placeholder = { Text(hint, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            colors = bookmarkFieldColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        Button(
            onClick = { submit() },
            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Add bookmark") }
    }
}

@Composable
private fun bookmarkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AmberGold,
    unfocusedBorderColor = FieldBorder,
    cursorColor = AmberGold,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
)

@Composable
private fun BookmarkRow(bookmark: Bookmark, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = "Jump to this bookmark", role = Role.Button, onClick = onClick)
                .padding(start = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = bookmarkRowDescription(bookmark) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bookmark.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            Text(
                text = bookmarkTimeLabel(bookmark),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Bookmark actions for ${bookmark.title}", tint = TextSecondary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

@Composable
private fun RenameBookmarkDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = {
            Text(
                text = "Rename bookmark",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name", color = TextSecondary) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
                colors = bookmarkFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Save", color = AmberGold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
