package com.chapterplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.OfflineHeader
import com.chapterplayer.domain.browse.isFavourite
import com.chapterplayer.ui.details.BookActionsHost
import com.chapterplayer.ui.details.rememberBookActions
import com.chapterplayer.ui.components.SettingsAction
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (bookId: String) -> Unit,
    onGroupClick: (view: LibraryView, key: String) -> Unit,
    onOpenSettings: () -> Unit,
    /** The "Add a server" button of the empty state: the Servers page of Settings. */
    onOpenServers: () -> Unit,
    /** The chart button: the listening statistics. */
    onOpenStats: () -> Unit,
    viewModel: LibraryViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sheetOpen by remember { mutableStateOf(false) }
    var librariesOpen by remember { mutableStateOf(false) }
    val bookActions = rememberBookActions()
    val selection = koinViewModel<BookSelectionViewModel>()
    val selectionState by selection.uiState.collectAsStateWithLifecycle()
    // Selection mode is for the book list; the grouped views list authors, series and so on.
    SelectionEffects(selection, selectionState, if (state.view.isGrouped) emptyList() else state.items, snackbarHostState)
    LaunchedEffect(state.view) { if (state.view.isGrouped && selectionState.active) selection.close() }

    // Sync errors show as a snackbar; a new message while one shows replaces it with the full list.
    LaunchedEffect(state.syncError) {
        state.syncError?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.onErrorDismissed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectionState.active) SelectionTopBar(selectionState, selection) else TopAppBar(
                // Five 48 dp buttons at most, so "Library" keeps room on a 360 dp phone; one line whatever the font size.
                title = { Text("Library", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { librariesOpen = true }) {
                        Icon(Icons.Default.Checklist, contentDescription = "Libraries", tint = TextSecondary)
                    }
                    LayoutAction(state.layout, viewModel::onLayoutSelected)
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
                            color = AmberGold,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = viewModel::onRefreshRequested) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                        }
                    }
                    LibraryMoreMenu(
                        canSelect = !state.view.isGrouped && state.items.isNotEmpty(),
                        onSelect = { selection.start() },
                        onOpenStats = onOpenStats,
                    )
                    SettingsAction(onOpenSettings)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDeep,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
            OfflineHeader()

            if (state.hasBooks) {
                LibraryViewRow(selected = state.view, onSelected = viewModel::onViewSelected)
                FilterChipRow(
                    selected = state.chip,
                    onSelected = viewModel::onChipSelected,
                    onOpenSheet = { sheetOpen = true },
                    serverChip = state.serverChip,
                    onClearServers = viewModel::onServerFilterCleared
                )
            }

            PullToRefreshBox(
                isRefreshing = state.isSyncing,
                onRefresh = viewModel::onRefreshRequested,
                modifier = Modifier.fillMaxSize()
            ) {
                LibraryBody(
                    state, viewModel, onBookClick, onGroupClick, onOpenServers, onOpenLibraries = { librariesOpen = true },
                    onBookLongClick = bookActions::open,
                    selection = selectionState.selected.takeIf { selectionState.active },
                    onToggleSelected = selection::toggle,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = SurfaceElevated,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = AmberGold
            )
        }
    }

    if (librariesOpen) {
        LibrariesSheet(onDismiss = { librariesOpen = false })
    }

    BookActionsHost(
        bookActions, snackbarHostState, onOpenBook = onBookClick, onOpenGroup = onGroupClick,
        onSelect = { id: String -> selection.start(id) }.takeIf { !state.view.isGrouped },
    )

    if (sheetOpen) {
        FilterSheet(
            query = state.query,
            onDownloadSelected = viewModel::onDownloadFilterSelected,
            onProgressSelected = viewModel::onProgressFilterSelected,
            onSortSelected = viewModel::onSortSelected,
            onReset = viewModel::onClearFilters,
            onDismiss = { sheetOpen = false },
            serverOptions = state.serverOptions,
            onServerToggled = viewModel::onServerToggled,
            onFavouritesOnlyChanged = viewModel::onFavouritesOnlyChanged,
        )
    }
}

/** The top bar's "More": Select books (the book view with books) and Statistics. */
@Composable
private fun LibraryMoreMenu(canSelect: Boolean, onSelect: () -> Unit, onOpenStats: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SurfaceElevated) {
            if (canSelect) {
                DropdownMenuItem(
                    text = { Text("Select books", color = MaterialTheme.colorScheme.onSurface) },
                    leadingIcon = { Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = TextSecondary) },
                    onClick = {
                        open = false
                        onSelect()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Statistics", color = MaterialTheme.colorScheme.onSurface) },
                leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null, tint = TextSecondary) },
                onClick = {
                    open = false
                    onOpenStats()
                },
            )
        }
    }
}

@Composable
private fun LibraryBody(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    onBookClick: (String) -> Unit,
    onGroupClick: (LibraryView, String) -> Unit,
    onOpenServers: () -> Unit,
    onOpenLibraries: () -> Unit,
    onBookLongClick: (String) -> Unit,
    selection: Set<String>? = null,
    onToggleSelected: (String) -> Unit = {},
) {
    when {
        state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AmberGold)
        }

        !state.hasServers -> NoServersState(onOpenServers)
        state.allServersNeedSignIn && !state.hasBooks -> SignInNeededState(state.signInRequiredNames)
        !state.hasBooks && state.allLibrariesHidden -> AllLibrariesHiddenState(onOpenLibraries)
        !state.hasBooks && state.isSyncing -> SyncingState()
        !state.hasBooks -> NothingYetState()
        state.nothingDownloaded -> NothingDownloadedState()
        state.emptyBecauseOfFilter -> FilteredOutState(onClear = viewModel::onClearFilters)
        state.isContentEmpty -> EmptyViewState(state.view)
        state.view.isGrouped -> GroupList(
            groups = state.groups,
            onGroupClick = { group -> onGroupClick(state.view, group.key) },
            modifier = Modifier.fillMaxSize(),
            fastScroll = state.fastScroll
        )

        else -> BookLayout(
            items = state.items,
            layout = state.layout,
            isDimmed = state::dimmed,
            onBookClick = onBookClick,
            modifier = Modifier.fillMaxSize(),
            fastScroll = state.fastScroll,
            onBookLongClick = onBookLongClick,
            isFavourite = { isFavourite(it, state.favouriteIds) },
            selection = selection,
            onToggleSelected = onToggleSelected,
        )
    }
}
