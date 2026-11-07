package com.chapterplayer.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.LocalPlatformFeatures
import com.chapterplayer.ui.library.CoverArt
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What of a screen's book long-press menu shows. */
internal enum class BookActionsMode { CLOSED, ACTIONS, CHOOSE_COPY, CONFIRM_REMOVE }

/**
 * Which book a screen's long-press menu is about. [open] shows the menu; the book stays chosen after it closes, so the
 * result of its last action (a mark can wait for the servers for a few seconds) still reaches the snackbar.
 */
@Stable
class BookActionsState {
    var bookId by mutableStateOf<String?>(null)
        private set
    internal var mode by mutableStateOf(BookActionsMode.CLOSED)

    fun open(bookId: String) {
        this.bookId = bookId
        mode = BookActionsMode.ACTIONS
    }

    internal fun close() {
        mode = BookActionsMode.CLOSED
    }
}

@Composable
fun rememberBookActions(): BookActionsState = remember { BookActionsState() }

/**
 * The book long-press menu of a screen: the actions sheet, then the "Play from" / "Download from" sheet or the "Remove
 * download?" question an action can lead to, and the results in [snackbar]. It runs on a [BookDetailsViewModel] built
 * for the menu (`quickActions`: nothing is fetched on open), one per book, held by the screen, so it follows exactly
 * the details page's rules.
 */
@Composable
fun BookActionsHost(
    actions: BookActionsState,
    snackbar: SnackbarHostState,
    onOpenBook: (bookId: String) -> Unit,
    onOpenGroup: (LibraryView, key: String) -> Unit,
) {
    // One channel for every book's results, so a slow mark still reports after another book's menu opened.
    val scope = rememberCoroutineScope()
    val results = remember(actions) { BookActionResults(scope) }
    LaunchedEffect(results, snackbar) {
        for (text in results.results) snackbar.showSnackbar(text)
    }
    val bookId = actions.bookId ?: return
    val viewModel = koinViewModel<BookDetailsViewModel>(key = "book-actions-$bookId", parameters = { parametersOf(bookId, true) })
    SideEffect { results.follow(viewModel, viewModel.message, viewModel::onMessageShown) }
    if (actions.mode == BookActionsMode.CLOSED) return

    // The menu's state is followed only while something of it shows.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloads = LocalPlatformFeatures.current.downloads
    // The book left the library (a sync or a removed server) while its menu was open.
    LaunchedEffect(state.phase) {
        if (state.phase == DetailsPhase.NOT_FOUND) {
            viewModel.onSourceSheetDismissed()
            actions.close()
            results.report(BOOK_GONE_TEXT)
        }
    }
    when (actions.mode) {
        BookActionsMode.CLOSED -> Unit
        BookActionsMode.ACTIONS -> BookActionsSheet(
            state = state,
            items = bookActionItems(state, downloads, upNextAvailable = LocalPlatformFeatures.current.playback),
            onDismiss = actions::close,
            onItem = { item ->
                actions.mode = afterAction(item, viewModel, bookId, onOpenBook, onOpenGroup, state)
            },
        )
        BookActionsMode.CHOOSE_COPY -> {
            // The sheet the action opened arrives with the next state; if it never does, the menu closes.
            LaunchedEffect(viewModel) {
                withTimeoutOrNull(CHOOSE_COPY_WAIT_MS) { snapshotFlow { state.sourceSheet }.first { it != null } } ?: run {
                    // Forget the sheet too, so it cannot show up with a later menu.
                    viewModel.onSourceSheetDismissed()
                    actions.close()
                }
            }
            state.sourceSheet?.let { sheet ->
                SourceSheet(
                    sheet,
                    SourceActions(
                        onSourceChosen = { id ->
                            viewModel.onSourceChosen(id)
                            actions.close()
                        },
                        onSourceSheetDismissed = {
                            viewModel.onSourceSheetDismissed()
                            actions.close()
                        },
                        onNotSameBook = {}, onMergeWith = {}, onMergeQueryChanged = {}, onMergeCandidateChosen = {}, onMergeSheetDismissed = {},
                    ),
                )
            }
        }
        BookActionsMode.CONFIRM_REMOVE -> RemoveDownloadDialog(
            onConfirm = {
                viewModel.onRemoveDownloadConfirmed()
                actions.close()
            },
            onDismiss = {
                viewModel.onRemoveDownloadDismissed()
                actions.close()
            },
        )
    }
}

private const val CHOOSE_COPY_WAIT_MS = 2_000L
private const val BOOK_GONE_TEXT = "This book is no longer in your library"

/** Runs a row's action and says what shows next. */
private fun afterAction(
    item: BookActionItem,
    viewModel: BookDetailsViewModel,
    bookId: String,
    onOpenBook: (String) -> Unit,
    onOpenGroup: (LibraryView, String) -> Unit,
    state: BookDetailsUiState,
): BookActionsMode {
    fun choose(opened: Boolean) = if (opened) BookActionsMode.CHOOSE_COPY else BookActionsMode.CLOSED
    return when (item.kind) {
        BookActionKind.PLAY -> choose(viewModel.onQuickPlay())
        BookActionKind.PLAY_NEXT -> BookActionsMode.CLOSED.also { viewModel.onUpNextClicked(front = true) }
        BookActionKind.UP_NEXT -> BookActionsMode.CLOSED.also {
            if (state.inUpNext) viewModel.onRemoveFromUpNextClicked() else viewModel.onUpNextClicked(front = false)
        }
        BookActionKind.FAVOURITE -> BookActionsMode.CLOSED.also { viewModel.onFavouriteClicked() }
        BookActionKind.MARK -> BookActionsMode.CLOSED.also { viewModel.onMarkClicked() }
        BookActionKind.DOWNLOAD -> when (item.downloadPhase) {
            DetailsDownloadPhase.IDLE, DetailsDownloadPhase.FAILED -> choose(viewModel.onQuickDownload())
            DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING -> BookActionsMode.CLOSED.also { viewModel.onPauseDownloadClicked() }
            DetailsDownloadPhase.PAUSED -> BookActionsMode.CLOSED.also { viewModel.onResumeDownloadClicked() }
            DetailsDownloadPhase.DONE -> BookActionsMode.CONFIRM_REMOVE.also { viewModel.onRemoveDownloadClicked() }
            DetailsDownloadPhase.HIDDEN, null -> BookActionsMode.CLOSED
        }
        BookActionKind.DOWNLOAD_ANOTHER -> choose(viewModel.onQuickDownloadAnotherCopy())
        BookActionKind.COPY_DOWNLOAD -> {
            val id = item.copyId ?: return BookActionsMode.CLOSED
            when (item.downloadPhase) {
                DetailsDownloadPhase.FAILED -> BookActionsMode.CLOSED.also { viewModel.onCopyRetryClicked(id) }
                DetailsDownloadPhase.QUEUED, DetailsDownloadPhase.DOWNLOADING -> BookActionsMode.CLOSED.also { viewModel.onCopyPauseClicked(id) }
                DetailsDownloadPhase.PAUSED -> BookActionsMode.CLOSED.also { viewModel.onCopyResumeClicked(id) }
                DetailsDownloadPhase.DONE -> BookActionsMode.CONFIRM_REMOVE.also { viewModel.onCopyRemoveClicked(id) }
                else -> BookActionsMode.CLOSED
            }
        }
        BookActionKind.SERIES -> BookActionsMode.CLOSED.also { state.links.series?.let { onOpenGroup(LibraryView.SERIES, it.key) } }
        BookActionKind.MORE_BY -> BookActionsMode.CLOSED.also { state.links.authors.firstOrNull()?.let { onOpenGroup(LibraryView.AUTHORS, it.key) } }
        BookActionKind.DETAILS -> BookActionsMode.CLOSED.also { onOpenBook(bookId) }
    }
}

/** The sheet itself: the book's cover, title and author, then one row per action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionsSheet(
    state: BookDetailsUiState,
    items: List<BookActionItem>,
    onDismiss: () -> Unit,
    onItem: (BookActionItem) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val book = state.book
            if (book == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AmberGold)
                }
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) { CoverArt(book, Modifier.size(56.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = TextSecondary.copy(alpha = 0.2f))
            items.forEach { item -> ActionRow(item) { onItem(item) } }
        }
    }
}

@Composable
private fun ActionRow(item: BookActionItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(enabled = item.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.enabled) MaterialTheme.colorScheme.onSurface else TextSecondary.copy(alpha = 0.6f),
        )
        item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
    }
}

/** "Remove download?", worded as on the details page and in the player menu. */
@Composable
private fun RemoveDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = { Text("Remove download?") },
        text = { Text("The book's files are deleted from this phone. It stays on the server and in your library.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep", color = TextSecondary) } },
    )
}
