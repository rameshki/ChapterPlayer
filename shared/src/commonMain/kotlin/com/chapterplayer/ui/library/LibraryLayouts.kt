package com.chapterplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.DownloadBadgeKind
import com.chapterplayer.domain.browse.coverOverlay
import com.chapterplayer.ui.components.BookCard
import com.chapterplayer.ui.components.BookCoverPlaceholder
import com.chapterplayer.ui.components.DimmedCoverOverlay
import com.chapterplayer.ui.components.DownloadBadge
import com.chapterplayer.ui.components.FinishedCheck
import com.chapterplayer.ui.components.ReadingProgressBar
import com.chapterplayer.ui.components.SourceBadge
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

internal fun LibraryLayout.label(): String = when (this) {
    LibraryLayout.LARGE_GRID -> "Large grid"
    LibraryLayout.SMALL_GRID -> "Small grid"
    LibraryLayout.LIST -> "List"
}

internal fun booksLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

/** The toolbar button that opens a small menu to choose the layout. */
@Composable
fun LayoutAction(layout: LibraryLayout, onSelected: (LibraryLayout) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = when (layout) {
                    LibraryLayout.LARGE_GRID -> Icons.Default.GridView
                    LibraryLayout.SMALL_GRID -> Icons.Default.ViewModule
                    LibraryLayout.LIST -> Icons.Default.ViewList
                },
                contentDescription = "Layout",
                tint = TextSecondary
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SurfaceElevated) {
            LibraryLayout.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label(), color = MaterialTheme.colorScheme.onSurface) },
                    trailingIcon = {
                        if (option == layout) Icon(Icons.Default.Check, contentDescription = "Selected", tint = AmberGold)
                    },
                    onClick = {
                        open = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

/**
 * The books as a large grid (two across), a small grid (three across) or a list. Dimmed books
 * (offline, not downloaded) cannot be opened.
 */
@Composable
fun BookLayout(
    items: List<BrowseItem>,
    layout: LibraryLayout,
    isDimmed: (BrowseItem) -> Boolean,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when (layout) {
        LibraryLayout.LIST -> LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "count") { CountHeader(items.size) }
            items(items, key = { it.book.id }) { item ->
                BookRow(item, dimmed = isDimmed(item), onClick = { onBookClick(item.book.id) })
            }
        }

        LibraryLayout.LARGE_GRID, LibraryLayout.SMALL_GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(if (layout == LibraryLayout.LARGE_GRID) 2 else 3),
            modifier = modifier,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "count", span = { GridItemSpan(maxLineSpan) }) { CountHeader(items.size, horizontalPadding = 0) }
            items(items, key = { it.book.id }) { item ->
                BookCard(
                    book = item.book,
                    progress = item.progress,
                    downloadState = item.downloadState,
                    dimmed = isDimmed(item),
                    onClick = { onBookClick(item.book.id) }
                )
            }
        }
    }
}

@Composable
private fun CountHeader(count: Int, horizontalPadding: Int = 16) {
    Text(
        text = booksLabel(count),
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = horizontalPadding.dp, vertical = 8.dp)
    )
}

/** One book as a row: a small cover, title and author, and the progress and download marks. */
@Composable
private fun BookRow(item: BrowseItem, dimmed: Boolean, onClick: () -> Unit) {
    val book = item.book
    val overlay = coverOverlay(item.progress, item.downloadState, dimmed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
            CoverArt(book, Modifier.size(56.dp))
            DimmedCoverOverlay(overlay.cloud)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            overlay.progressFraction?.let { ReadingProgressBar(it, Modifier.padding(top = 6.dp)) }
        }
        SourceBadge(book.source)
        if (overlay.badge != DownloadBadgeKind.NONE) DownloadBadge(item.downloadState)
        if (overlay.finished) FinishedCheck()
    }
}

/** The cover image, or the gradient placeholder when the book has none. */
@Composable
internal fun CoverArt(book: AudioBook, modifier: Modifier = Modifier) {
    if (book.coverUrl != null) {
        AsyncImage(
            model = book.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        BookCoverPlaceholder(title = book.title, modifier = modifier)
    }
}
