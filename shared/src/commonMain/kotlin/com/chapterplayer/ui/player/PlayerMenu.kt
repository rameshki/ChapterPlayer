package com.chapterplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.playback.MarkAction
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/** What the player menu does besides the actions of [PlayerMenuViewModel]: the three places it navigates to. */
class PlayerMenuActions(
    val onOpenDetails: (bookId: String) -> Unit,
    val onOpenGroup: (LibraryView, key: String) -> Unit,
    val onOpenSettings: () -> Unit,
    val onDownload: () -> Unit,
    val onMark: () -> Unit,
    val onRemoveConfirmed: () -> Unit,
    val onRemoveDismissed: () -> Unit,
    val onMessageShown: () -> Unit,
)

/** The ⋮ button at the top right of the player and the menu it opens. */
@Composable
fun PlayerMenuButton(menu: PlayerMenuState, actions: PlayerMenuActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier.size(48.dp)) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SurfaceElevated) {
            val close = { open = false }
            val bookId = menu.bookId
            if (bookId != null) {
                MenuItem("Book details", close = close) { actions.onOpenDetails(bookId) }
            }
            menu.moreBy?.let { author ->
                MenuItem("More by ${author.title}", close = close) { actions.onOpenGroup(LibraryView.AUTHORS, author.key) }
            }
            menu.series?.let { series ->
                MenuItem("Open series", close = close) { actions.onOpenGroup(LibraryView.SERIES, series.key) }
            }
            if (menu.download.kind != MenuDownloadKind.HIDDEN) {
                MenuItem(menu.download.label, menu.download.subtitle, menu.download.enabled, close) { actions.onDownload() }
            }
            menu.mark?.let { mark ->
                MenuItem(if (mark == MarkAction.MarkFinished) "Mark as finished" else "Mark as not finished", close = close) { actions.onMark() }
            }
            MenuItem("Settings", close = close) { actions.onOpenSettings() }
        }
    }
}

/** One menu entry; tapping it closes the menu, then runs the action. */
@Composable
private fun MenuItem(
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    close: () -> Unit,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        },
        enabled = enabled,
        onClick = {
            close()
            onClick()
        },
    )
}

/** The confirmation for "Remove download", shown over the player. */
@Composable
fun PlayerMenuDialogs(menu: PlayerMenuState, actions: PlayerMenuActions) {
    if (menu.confirmingRemove) {
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
