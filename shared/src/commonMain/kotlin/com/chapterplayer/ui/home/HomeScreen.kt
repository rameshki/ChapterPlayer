package com.chapterplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.components.BookCard
import com.chapterplayer.ui.components.ContinueReadingStrip
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.components.OfflineHeader
import com.chapterplayer.ui.components.SettingsAction
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBookClick: (bookId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("ChapterPlayer", style = MaterialTheme.typography.headlineSmall) },
            actions = { SettingsAction(onOpenSettings) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDeep,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        OfflineHeader()

        val nothingToShow = state.inProgress.isEmpty() && state.recentlyAdded.isEmpty()
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AmberGold)
            }

            !state.hasServers -> ScrollingEmpty {
                EmptyState(
                    icon = Icons.Default.Home,
                    message = "Add a server to see your audiobooks here."
                ) {
                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberGold,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) { Text("Add a server") }
                }
            }

            nothingToShow -> ScrollingEmpty {
                if (state.hasBooks) {
                    if (state.downloadedFilterOn) {
                        EmptyState(Icons.Default.CloudDownload, "Nothing downloaded here yet")
                    } else {
                        EmptyState(Icons.Default.Warning, "No books match the current filters.")
                    }
                } else if (state.isSyncing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AmberGold)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Syncing your libraries...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                    }
                } else {
                    EmptyState(Icons.Default.Home, "Nothing here yet. Pull to refresh in Library.")
                }
            }

            else -> HomeShelves(state, onBookClick)
        }
    }
}

@Composable
private fun ScrollingEmpty(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun HomeShelves(state: HomeUiState, onBookClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        if (state.inProgress.isNotEmpty()) {
            ContinueReadingStrip(
                books = state.inProgress.map { it.book },
                progressMap = state.inProgress.mapNotNull { it.progress }.associateBy { it.bookId },
                onBookClick = onBookClick,
                dimmedIds = state.inProgress.filter { state.dimmed(it) }.map { it.book.id }.toSet()
            )
            Spacer(Modifier.height(24.dp))
        }

        if (state.recentlyAdded.isNotEmpty()) {
            Text(
                text = "Recently added",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.recentlyAdded, key = { it.book.id }) { item ->
                    BookCard(
                        book = item.book,
                        progress = item.progress,
                        downloadState = item.downloadState,
                        dimmed = state.dimmed(item),
                        onClick = { onBookClick(item.book.id) },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }
    }
}
