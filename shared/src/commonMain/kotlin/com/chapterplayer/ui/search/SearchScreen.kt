package com.chapterplayer.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.domain.browse.isDimmed
import com.chapterplayer.domain.browse.sourcesDescription
import com.chapterplayer.domain.browse.sourcesMark
import com.chapterplayer.ui.components.BookCard
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.components.OfflineHeader
import com.chapterplayer.ui.components.ServerFilterHint
import com.chapterplayer.ui.components.SettingsAction
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.FieldBorder
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBookClick: (bookId: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SearchViewModel = koinViewModel()
) {
    val text by viewModel.queryText.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Search", style = MaterialTheme.typography.headlineSmall) },
            actions = { SettingsAction(onOpenSettings) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDeep,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        OfflineHeader()
        if (state.serverFilterOn) ServerFilterHint(onClear = viewModel::onServerFilterCleared)

        OutlinedTextField(
            value = text,
            onValueChange = viewModel::onQueryChange,
            singleLine = true,
            placeholder = { Text("Title, author, narrator or series", color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextSecondary)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AmberGold,
                unfocusedBorderColor = FieldBorder,
                cursorColor = AmberGold,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        when (state.status) {
            SearchStatus.PROMPT -> Centered {
                EmptyState(Icons.Default.Search, "Search your library by title, author, narrator or series")
            }

            SearchStatus.NO_RESULTS -> Centered {
                EmptyState(Icons.Default.Search, "No results for \"${state.query.trim()}\"")
            }

            SearchStatus.FILTERED_OUT -> Centered {
                if (state.downloadedFilterOn) {
                    EmptyState(Icons.Default.CloudDownload, "Nothing downloaded here yet")
                } else {
                    EmptyState(Icons.Default.Warning, "No books match the current filters.")
                }
            }

            SearchStatus.RESULTS -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.results, key = { it.book.id }) { item ->
                    BookCard(
                        book = item.book,
                        progress = item.progress,
                        downloadState = item.downloadState,
                        dimmed = isDimmed(item.downloadState, state.online, state.downloadedOnly),
                        onClick = { onBookClick(item.book.id) },
                        sourcesMark = sourcesMark(item),
                        sourcesDescription = sourcesDescription(item)
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) { content() }
}
