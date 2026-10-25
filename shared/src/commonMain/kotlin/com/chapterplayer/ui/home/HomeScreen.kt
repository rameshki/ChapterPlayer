package com.chapterplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.ResumeInfo
import com.chapterplayer.domain.browse.parseSeriesName
import com.chapterplayer.domain.browse.sourcesDescription
import com.chapterplayer.domain.browse.sourcesMark
import com.chapterplayer.ui.components.BookCard
import com.chapterplayer.ui.components.DimmedCoverOverlay
import com.chapterplayer.ui.library.CoverArt
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.components.ContinueReadingStrip
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.components.OfflineHeader
import com.chapterplayer.ui.components.ServerFilterHint
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
    /** The "Add a server" button of the empty state: the Servers page of Settings. */
    onOpenServers: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // A start can finish after the user moved on: open the player only while Home is still showing,
    // and through the navigator of the moment.
    val currentOpenPlayer by rememberUpdatedState(onOpenPlayer)
    val stillHere = remember { HomeVisible() }
    DisposableEffect(Unit) { onDispose { stillHere.value = false } }
    val openPlayerAfterStart: () -> Unit = remember { { if (stillHere.value) currentOpenPlayer() } }

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
        if (state.serverFilterOn) ServerFilterHint(onClear = viewModel::onServerFilterCleared)
        state.playError?.let { message ->
            val book = state.resume?.item?.book
            PlayErrorBanner(
                message = message,
                onRetry = { book?.let { viewModel.resume(it, openPlayerAfterStart) } ?: viewModel.dismissError() },
                onDismiss = viewModel::dismissError
            )
        }

        val nothingToShow = state.resume == null && state.continueSeries.isEmpty() &&
            state.inProgress.isEmpty() && state.recentlyAdded.isEmpty()
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
                        onClick = onOpenServers,
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

            else -> HomeShelves(state, onBookClick, onResume = { viewModel.resume(it, openPlayerAfterStart) })
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
private fun HomeShelves(state: HomeUiState, onBookClick: (String) -> Unit, onResume: (AudioBook) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
    ) {
        state.resume?.let { resume ->
            ResumeCard(
                resume = resume,
                canPlay = !state.dimmed(resume.item),
                starting = state.starting,
                onOpenDetails = { onBookClick(resume.item.book.id) },
                onResume = { onResume(resume.item.book) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(24.dp))
        }

        if (state.continueSeries.isNotEmpty()) {
            Text(
                text = "Continue Series",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() }
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.continueSeries, key = { it.book.id }) { item ->
                    Column(modifier = Modifier.width(150.dp)) {
                        BookCard(
                            book = item.book,
                            progress = item.progress,
                            downloadState = item.downloadState,
                            dimmed = state.dimmed(item),
                            onClick = { onBookClick(item.book.id) },
                            sourcesMark = sourcesMark(item),
                            sourcesDescription = sourcesDescription(item)
                        )
                        nextInSeriesCaption(item)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (state.inProgress.isNotEmpty()) {
            ContinueReadingStrip(
                books = state.inProgress.map { it.book },
                // Keyed by the entry's book: a merged entry's progress may come from its other copy.
                progressMap = state.inProgress.mapNotNull { item -> item.progress?.let { item.book.id to it } }.toMap(),
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
                        modifier = Modifier.width(150.dp),
                        sourcesMark = sourcesMark(item),
                        sourcesDescription = sourcesDescription(item)
                    )
                }
            }
        }
    }
}

private class HomeVisible { var value = true }

private fun nextInSeriesCaption(item: BrowseItem): String? =
    parseSeriesName(item.book.seriesName)?.name?.let { "Next in $it" }

/** The book to pick up again: cover, where it stopped, time left, and a Resume button. */
@Composable
private fun ResumeCard(
    resume: ResumeInfo,
    canPlay: Boolean,
    starting: Boolean,
    onOpenDetails: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book = resume.item.book
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceElevated)
            .clickable(onClickLabel = "Open details", onClick = onOpenDetails)
            .padding(12.dp)
    ) {
        Text(
            text = "Pick up where you left off",
            style = MaterialTheme.typography.labelLarge,
            color = AmberGold,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clearAndSetSemantics { contentDescription = "Cover art for ${book.title}" }
            ) {
                CoverArt(book, Modifier.fillMaxSize())
                DimmedCoverOverlay(!canPlay)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                resume.chapterTitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { resume.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .semantics { contentDescription = "${(resume.fraction * 100).toInt()} percent listened" },
                    color = AmberGold,
                    trackColor = SurfaceSecondary
                )
                Text(
                    text = resume.timeLeft,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onResume,
            enabled = canPlay && !starting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = AmberGold.copy(alpha = 0.4f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        ) {
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Resume", style = MaterialTheme.typography.titleMedium)
        }
        if (!canPlay) {
            Text(
                text = "Needs a connection to play",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun PlayErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry", color = AmberGold) }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss", color = TextSecondary) }
    }
}
