package com.chapterplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.BulkAction
import com.chapterplayer.ui.components.LocalPlatformFeatures
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextSecondary
import kotlinx.coroutines.flow.filterNotNull

/**
 * The top bar of selection mode: Close, "N selected" (or the progress of a running action, "Marking 37 of 1000…"),
 * Select all and the actions menu; while an action runs, Cancel and a progress bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(state: SelectionUiState, selection: BookSelectionViewModel) {
    Column {
        TopAppBar(
            navigationIcon = {
                if (!state.running) {
                    IconButton(onClick = selection::close) {
                        Icon(Icons.Default.Close, contentDescription = "Close selection", tint = TextSecondary)
                    }
                }
            },
            title = {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            actions = {
                val progress = state.progress
                if (progress != null) {
                    TextButton(onClick = selection::cancel, enabled = !progress.stopping) {
                        Text("Cancel", color = if (progress.stopping) TextSecondary else AmberGold)
                    }
                } else {
                    IconButton(onClick = selection::selectAll) {
                        Icon(Icons.Default.SelectAll, contentDescription = "Select all", tint = TextSecondary)
                    }
                    SelectionActionsMenu(state, selection::run)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDeep,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        state.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = AmberGold,
                trackColor = SurfaceSecondary,
            )
        }
    }
}

/** The actions of selection mode, offered as the platform allows (Up next needs a player, the download rows downloads). */
@Composable
private fun SelectionActionsMenu(state: SelectionUiState, onAction: (BulkAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val features = LocalPlatformFeatures.current
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Actions for the selected books", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SurfaceElevated) {
            val pick: (BulkAction) -> Unit = { action ->
                open = false
                onAction(action)
            }
            val on = state.canAct
            MenuRow("Mark as finished", BulkAction.MARK_FINISHED, on, onClick = pick)
            MenuRow("Mark as not finished", BulkAction.MARK_NOT_FINISHED, on, onClick = pick)
            if (state.favouriteAction == BulkAction.REMOVE_FAVOURITES) MenuRow("Remove from favourites", BulkAction.REMOVE_FAVOURITES, on, onClick = pick)
            else MenuRow("Add to favourites", BulkAction.ADD_FAVOURITES, on, onClick = pick)
            if (features.playback) MenuRow("Add to Up next", BulkAction.ADD_UP_NEXT, on, onClick = pick)
            if (features.downloads) {
                MenuRow(
                    "Download", BulkAction.DOWNLOAD, on && state.online,
                    note = if (state.online) null else "Needs a connection", onClick = pick,
                )
                MenuRow("Remove download", BulkAction.REMOVE_DOWNLOAD, on, onClick = pick)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, action: BulkAction, enabled: Boolean, note: String? = null, onClick: (BulkAction) -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else TextSecondary.copy(alpha = 0.6f))
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
            }
        },
        enabled = enabled,
        onClick = { onClick(action) },
    )
}

/**
 * What selection mode needs besides the top bar: the list as shown goes to the view model, system Back closes the
 * mode (or cancels a running action), the result shows in [snackbar], and "Remove N downloads?" asks.
 */
@Composable
fun SelectionEffects(selection: BookSelectionViewModel, state: SelectionUiState, items: List<BrowseItem>, snackbar: SnackbarHostState) {
    LaunchedEffect(selection, items) { selection.onItemsChanged(items) }
    LaunchedEffect(selection, snackbar) {
        selection.message.filterNotNull().collect { text ->
            selection.onMessageShown()
            snackbar.showSnackbar(text)
        }
    }
    NavigationEventHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = state.active,
        onBackCompleted = { selection.close() },
    )
    state.confirmRemoveCount?.let { count ->
        val what = if (count == 1) "1 download" else "$count downloads"
        AlertDialog(
            onDismissRequest = selection::dismissRemoveDownloads,
            containerColor = SurfaceElevated,
            title = { Text("Remove $what?") },
            text = { Text("The files are deleted from this phone. The books stay on their servers and in your library.") },
            confirmButton = {
                TextButton(onClick = selection::confirmRemoveDownloads) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = selection::dismissRemoveDownloads) { Text("Keep", color = TextSecondary) } },
        )
    }
}
