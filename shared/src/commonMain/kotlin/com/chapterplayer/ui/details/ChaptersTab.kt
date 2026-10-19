package com.chapterplayer.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.browse.formatClock
import com.chapterplayer.domain.browse.formatLength
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/** The chapter list as rows of the details list; keys are chapter indexes so hundreds of rows stay cheap. */
internal fun LazyListScope.chaptersTab(
    chapters: List<ChapterRow>,
    emptyText: String,
    tapsEnabled: Boolean,
    onChapterClick: (index: Int) -> Unit
) {
    if (chapters.isEmpty()) {
        item(key = "chapters-empty") {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp)
            )
        }
        return
    }
    chaptersNote(tapsEnabled)?.let { note ->
        item(key = "chapters-note") {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
    items(chapters, key = { "chapter-${it.index}" }) { ChapterListRow(it, tapsEnabled, onChapterClick) }
}

@Composable
private fun ChapterListRow(row: ChapterRow, tapsEnabled: Boolean, onClick: (Int) -> Unit) {
    val start = formatClock(row.startMs)
    val length = formatLength(row.durationMs)
    val description = buildString {
        append("Chapter ${row.index + 1}, ${row.title}, starts at $start, $length")
        if (row.isCurrent) append(", current chapter")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // Gives the accent bar a bounded height to fill.
            .height(IntrinsicSize.Min)
            .background(if (row.isCurrent) SurfaceElevated else Color.Transparent)
            .clickable(enabled = tapsEnabled, onClickLabel = "Play from this chapter", role = Role.Button) { onClick(row.index) }
            .semantics(mergeDescendants = true) {
                selected = row.isCurrent
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(if (row.isCurrent) AmberGold else Color.Transparent)
        )
        Text(
            text = "${row.index + 1}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.isCurrent) AmberGold else TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp).padding(end = 12.dp)
        )
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(text = start, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = length, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
