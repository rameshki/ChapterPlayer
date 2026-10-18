package com.chapterplayer.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.domain.browse.SeriesLink
import com.chapterplayer.ui.components.OfflineHeader
import com.chapterplayer.ui.library.CoverArt
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** One book: header, then About, Chapters or Series. */
@Composable
fun BookDetailsScreen(
    bookId: String,
    onBack: () -> Unit,
    onOpenBook: (bookId: String) -> Unit,
    onOpenGroup: (kind: LibraryView, key: String) -> Unit,
    viewModel: BookDetailsViewModel = koinViewModel(parameters = { parametersOf(bookId) })
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        DetailsTopBar(onBack)
        OfflineHeader()
        BookDetailsContent(
            state = state,
            onBack = onBack,
            onRetry = viewModel::retry,
            onSelectTab = viewModel::selectTab,
            onOpenBook = onOpenBook,
            onOpenGroup = onOpenGroup
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDeep)
    )
}

/** The screen below the top bar; it takes state and callbacks only. */
@Composable
fun BookDetailsContent(
    state: BookDetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectTab: (DetailsTab) -> Unit,
    onOpenBook: (bookId: String) -> Unit,
    onOpenGroup: (kind: LibraryView, key: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val book = state.book
    when {
        state.phase == DetailsPhase.NOT_FOUND -> Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "This book is no longer available",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberGold,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("Back") }
        }

        state.phase == DetailsPhase.LOADING || book == null -> Box(
            modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = AmberGold) }

        else -> Column(modifier = modifier.fillMaxSize()) {
            if (state.loadingDetails) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = AmberGold,
                    trackColor = SurfaceElevated
                )
            }
            state.error?.let { ErrorBanner(it, onRetry) }
            LoadedList(state, book, onSelectTab, onOpenBook, onOpenGroup)
        }
    }
}

@Composable
private fun LoadedList(
    state: BookDetailsUiState,
    book: AudioBook,
    onSelectTab: (DetailsTab) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenGroup: (LibraryView, String) -> Unit
) {
    val hasSeries = state.series.isNotEmpty()
    val tab = effectiveTab(state.selectedTab, hasSeries)
    // One scroll container: the header and tabs are the first items, so they scroll away with the list.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "header") { DetailsHeader(state, book, onOpenGroup) }
        item(key = "tabs") { DetailsTabs(tab, hasSeries, onSelectTab) }
        when (tab) {
            DetailsTab.CHAPTERS -> chaptersTab(state.chapters)
            DetailsTab.ABOUT -> aboutTab(state.about, state.links, onOpenGroup)
            DetailsTab.SERIES -> seriesTab(state.series, state.links.series, onOpenBook, onOpenGroup)
        }
    }
}

@Composable
private fun DetailsHeader(
    state: BookDetailsUiState,
    book: AudioBook,
    onOpenGroup: (LibraryView, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clearAndSetSemantics { contentDescription = "Cover art for ${book.title}" }
            ) {
                CoverArt(book, Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() }
                )
                state.about?.authorLine?.let { InfoLine(it) }
                state.about?.narratorLine?.let { InfoLine(it) }
                lengthLine(state.lengthText, state.timeLeft)?.let { InfoLine(it) }
                state.links.series?.let { SeriesLine(it, onOpenGroup) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {},
            enabled = state.resumeEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = AmberGold.copy(alpha = 0.4f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        ) { Text("Resume", style = MaterialTheme.typography.titleMedium) }
        if (!state.resumeEnabled) {
            Text(
                text = "Playback arrives in a later update",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SeriesLine(link: SeriesLink, onOpenGroup: (LibraryView, String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onOpenGroup(LibraryView.SERIES, link.key) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = seriesLineText(link),
            style = MaterialTheme.typography.bodyMedium,
            color = AmberGold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open series",
            tint = TextSecondary
        )
    }
}

@Composable
private fun DetailsTabs(selected: DetailsTab, hasSeries: Boolean, onSelect: (DetailsTab) -> Unit) {
    val tabs = visibleTabs(hasSeries)
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        containerColor = BackgroundDeep,
        contentColor = AmberGold
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                selectedContentColor = AmberGold,
                unselectedContentColor = TextSecondary,
                modifier = Modifier.heightIn(min = 48.dp),
                text = { Text(tabLabel(tab), style = MaterialTheme.typography.titleSmall) }
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
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
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Retry", color = AmberGold)
        }
    }
}
