package com.chapterplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextSecondary

/**
 * Horizontally scrollable strip showing in-progress books.
 * Appears at the top of the Library and Home screens. Books in [dimmedIds] cannot play now
 * (offline and not downloaded): they get the cloud overlay and ignore taps.
 */
@Composable
fun ContinueReadingStrip(
    books: List<AudioBook>,
    progressMap: Map<String, ReadingProgress>,
    onBookClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    dimmedIds: Set<String> = emptySet()
) {
    Column(modifier = modifier) {
        Text(
            text = "Continue Reading",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(books, key = { it.id }) { book ->
                val progress = progressMap[book.id]
                ContinueReadingCard(
                    book = book,
                    progress = progress,
                    dimmed = book.id in dimmedIds,
                    onClick = { onBookClick(book.id) }
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    book: AudioBook,
    progress: ReadingProgress?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick)
    ) {
        // Cover
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
        ) {
            if (book.coverUrl != null) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                BookCoverPlaceholder(title = book.title, modifier = Modifier.fillMaxWidth())
            }
            DimmedCoverOverlay(dimmed)
        }

        // Progress bar at bottom of cover
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = AmberGold,
                trackColor = SurfaceSecondary
            )
        }

        // Title
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (progress != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${(progress.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberGold
                )
            }
        }
    }
}
