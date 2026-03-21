package com.chapterplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.BookCard
import com.chapterplayer.ui.components.ContinueReadingStrip
import com.chapterplayer.ui.components.FilterPills
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBookClick: (bookId: String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface any sync errors as a snackbar
    LaunchedEffect(uiState.syncError) {
        uiState.syncError?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.onErrorDismissed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDeep)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top app bar
            TopAppBar(
                title = {
                    Text(
                        text = "ChapterPlayer",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 16.dp),
                            color = AmberGold,
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = viewModel::onRefreshRequested) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync library",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BackgroundDeep,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            // Main content
            when {
                !uiState.hasServersConfigured -> {
                    EmptyStateNoServers(modifier = Modifier.fillMaxSize())
                }

                uiState.isSyncing && uiState.allBooks.isEmpty() -> {
                    InitialLoadingState(modifier = Modifier.fillMaxSize())
                }

                else -> {
                    LibraryContent(
                        uiState = uiState,
                        onBookClick = onBookClick,
                        onFilterSelected = viewModel::onFilterSelected,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Snackbar for errors
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { snackbarData ->
            Snackbar(
                snackbarData = snackbarData,
                containerColor = SurfaceElevated,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = AmberGold
            )
        }
    }
}

@Composable
private fun LibraryContent(
    uiState: LibraryUiState,
    onBookClick: (String) -> Unit,
    onFilterSelected: (com.chapterplayer.ui.components.LibraryFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        // Continue Reading strip — full-width
        if (uiState.showContinueReading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    ContinueReadingStrip(
                        books = uiState.inProgressBooks,
                        progressMap = uiState.progressMap,
                        onBookClick = onBookClick
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Filter pills — full-width
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterPills(
                    selectedFilter = uiState.activeFilter,
                    onFilterSelected = onFilterSelected
                )
            }
        }

        // Section header
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "${uiState.filteredBooks.size} books",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // Book grid
        items(uiState.filteredBooks, key = { it.id }) { book ->
            val progress = uiState.progressMap[book.id]
            Box(modifier = Modifier.padding(
                start = if (uiState.filteredBooks.indexOf(book) % 2 == 0) 16.dp else 0.dp,
                end = if (uiState.filteredBooks.indexOf(book) % 2 == 1) 16.dp else 0.dp
            )) {
                BookCard(
                    book = book,
                    progress = progress,
                    onClick = { onBookClick(book.id) }
                )
            }
        }

        // Empty filtered state
        if (uiState.filteredBooks.isEmpty() && !uiState.isSyncing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyFilteredState(
                    filter = uiState.activeFilter,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun EmptyStateNoServers(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LibraryBooks,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = TextSecondary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No servers connected",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Go to Settings to add your Audiobookshelf or Plex server.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InitialLoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AmberGold)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Loading library...",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyFilteredState(
    filter: com.chapterplayer.ui.components.LibraryFilter,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No ${filter.name.lowercase()} books found",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
