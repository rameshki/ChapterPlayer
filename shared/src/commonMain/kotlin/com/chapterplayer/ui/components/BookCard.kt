package com.chapterplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.domain.browse.DownloadBadgeKind
import com.chapterplayer.domain.browse.coverOverlay
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/**
 * Grid card for a single audiobook in the Library screen.
 * Displays cover art (or gradient placeholder), source badge,
 * title, author, reading progress, download state and the offline dimming.
 */
@Composable
fun BookCard(
    book: AudioBook,
    progress: ReadingProgress? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    dimmed: Boolean = false
) {
    val overlay = coverOverlay(progress, downloadState, dimmed)
    Card(
        modifier = modifier
            .fillMaxWidth()
            // A dimmed book cannot play now (offline, not downloaded), but its details still open.
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Cover art
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            ) {
                if (book.coverUrl != null) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = "Cover art for ${book.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    BookCoverPlaceholder(
                        title = book.title,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Source badge — top-left corner
                SourceBadge(
                    source = book.source,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )

                if (overlay.badge != DownloadBadgeKind.NONE) {
                    DownloadBadge(downloadState, Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
                if (overlay.finished) {
                    FinishedCheck(Modifier.align(Alignment.BottomEnd).padding(6.dp))
                }
                overlay.progressFraction?.let {
                    ReadingProgressBar(it, Modifier.align(Alignment.BottomCenter))
                }
                DimmedCoverOverlay(overlay.cloud)
            }

            // Metadata
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
