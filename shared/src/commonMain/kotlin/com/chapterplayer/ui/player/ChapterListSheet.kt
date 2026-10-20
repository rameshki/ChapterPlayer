package com.chapterplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.ChapterRow
import com.chapterplayer.domain.browse.formatClock
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextSecondary

/** All chapters; opens scrolled to the current one. A tap jumps to the chapter and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    chapters: List<ChapterRow>,
    currentIndex: Int?,
    onChapterSelected: (index: Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).semantics { heading() }
            )
            val start = (currentIndex ?: 0).coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(chapters, key = { "chapter-${it.index}" }) { row ->
                    SheetChapterRow(row) {
                        onChapterSelected(row.index)
                        onDismiss()
                    }
                }
                item(key = "end-space") { Box(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SheetChapterRow(row: ChapterRow, onClick: () -> Unit) {
    val start = formatClock(row.startMs)
    val description = buildString {
        append("Chapter ${row.index + 1}, ${row.title}, starts at $start")
        if (row.isCurrent) append(", current chapter")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .height(IntrinsicSize.Min)
            .background(if (row.isCurrent) SurfaceSecondary else Color.Transparent)
            .clickable(onClickLabel = "Play from this chapter", role = Role.Button, onClick = onClick)
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
        Text(
            text = start,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
