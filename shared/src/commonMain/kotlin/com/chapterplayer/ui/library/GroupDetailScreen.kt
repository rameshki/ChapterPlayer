package com.chapterplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.EmptyState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import com.chapterplayer.domain.browse.isFavourite
import com.chapterplayer.ui.details.BookActionsHost
import com.chapterplayer.ui.details.rememberBookActions
import com.chapterplayer.ui.components.OfflineHeader
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The books of one author, narrator, series or collection, in the chosen layout and under the current filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    kind: LibraryView,
    groupKey: String,
    onBookClick: (bookId: String) -> Unit,
    onBack: () -> Unit,
    /** "Go to series" and "More by" of the book long-press menu. */
    onOpenGroup: (LibraryView, key: String) -> Unit = { _, _ -> },
    viewModel: GroupDetailViewModel = koinViewModel(parameters = { parametersOf(kind, groupKey) })
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bookActions = rememberBookActions()
    val snackbar = remember { SnackbarHostState() }
    val selection = koinViewModel<BookSelectionViewModel>()
    val selectionState by selection.uiState.collectAsStateWithLifecycle()
    SelectionEffects(selection, selectionState, state.items, snackbar)

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionState.active) SelectionTopBar(selectionState, selection) else TopAppBar(
            title = {
                Text(
                    text = state.title ?: kind.singularTitle,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                }
            },
            actions = {
                if (state.items.isNotEmpty()) {
                    IconButton(onClick = { selection.start() }) {
                        Icon(Icons.Default.CheckCircleOutline, contentDescription = "Select books", tint = TextSecondary)
                    }
                }
                LayoutAction(state.layout, viewModel::onLayoutSelected)
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDeep,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        OfflineHeader()

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AmberGold)
            }

            !state.exists -> ScrollingEmpty {
                EmptyState(Icons.Default.Warning, "This is no longer in your library.")
            }

            state.exists && state.nothingDownloaded -> NothingDownloadedState()

            state.emptyBecauseOfFilter -> FilteredOutState(onClear = viewModel::onClearFilters)

            else -> BookLayout(
                items = state.items,
                layout = state.layout,
                isDimmed = state::dimmed,
                onBookClick = onBookClick,
                modifier = Modifier.fillMaxSize(),
                onBookLongClick = bookActions::open,
                isFavourite = { isFavourite(it, state.favouriteIds) },
                selection = selectionState.selected.takeIf { selectionState.active },
                onToggleSelected = selection::toggle,
            )
        }
    }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    BookActionsHost(bookActions, snackbar, onOpenBook = onBookClick, onOpenGroup = onOpenGroup, onSelect = { id -> selection.start(id) })
}
