package com.chapterplayer.ui.library

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.BookGroup
import com.chapterplayer.domain.browse.LetterIndex
import com.chapterplayer.ui.components.FastScroller
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.TextSecondary

private val CoverSize = 44.dp
private val CoverStep = 22.dp
private const val MAX_COVERS = 3

/** Authors, narrators, series or collections: one row per group. A tap opens the group's books. */
@Composable
fun GroupList(
    groups: List<BookGroup>,
    onGroupClick: (BookGroup) -> Unit,
    modifier: Modifier = Modifier,
    /** The A to Z scroller's index over [groups]; empty shows no scroller. */
    fastScroll: List<LetterIndex> = emptyList()
) {
    Box(modifier = modifier) {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The strip sits over the right edge, so the rows leave it room when it is offered.
            contentPadding = PaddingValues(end = if (fastScroll.isEmpty()) 0.dp else 8.dp, bottom = 16.dp)
        ) {
            items(groups, key = { it.key }) { group ->
                GroupRow(group, onClick = { onGroupClick(group) })
            }
        }
        FastScroller(
            letters = fastScroll,
            isListScrolling = listState.isScrollInProgress,
            onScrollTo = { listState.scrollToItem(it) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun GroupRow(group: BookGroup, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OverlappingCovers(group)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = booksLabel(group.items.size),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

/** Up to three small covers, each overlapping the one before; decorative, so they have no description. */
@Composable
private fun OverlappingCovers(group: BookGroup) {
    val covers = group.items.take(MAX_COVERS)
    Box(modifier = Modifier.width(CoverSize + CoverStep * (MAX_COVERS - 1)).height(CoverSize)) {
        covers.forEachIndexed { index, item ->
            val shape = RoundedCornerShape(8.dp)
            CoverArt(
                book = item.book,
                modifier = Modifier
                    .offset(x = CoverStep * index)
                    .size(CoverSize)
                    .clip(shape)
                    .border(2.dp, BackgroundDeep, shape)
            )
        }
    }
}
