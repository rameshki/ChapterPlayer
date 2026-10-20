package com.chapterplayer.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.SeriesEntry
import com.chapterplayer.domain.browse.SeriesLink
import com.chapterplayer.ui.library.CoverArt
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

internal fun LazyListScope.seriesTab(
    entries: List<SeriesEntry>,
    link: SeriesLink?,
    onOpenBook: (String) -> Unit,
    onOpenGroup: (LibraryView, String) -> Unit
) {
    if (link != null) {
        item(key = "series-open") {
            OutlinedButton(
                onClick = { onOpenGroup(LibraryView.SERIES, link.key) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp)
            ) { Text("Open series", color = AmberGold) }
        }
    }
    items(entries, key = { "series-${it.item.book.serverId}-${it.item.book.id}" }) { entry ->
        SeriesRow(entry, onClick = { onOpenBook(entry.item.book.id) })
    }
}

@Composable
private fun SeriesRow(entry: SeriesEntry, onClick: () -> Unit) {
    val book = entry.item.book
    val sequence = book.seriesSequence?.trim().orEmpty()
    val description = buildString {
        append(book.title)
        if (sequence.isNotEmpty()) append(", number $sequence")
        if (entry.isCurrent) append(", this book")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            // The current book is marked and does not open itself again.
            .then(
                if (entry.isCurrent) Modifier.background(SurfaceElevated)
                else Modifier.clickable(role = Role.Button, onClick = onClick)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                selected = entry.isCurrent
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CoverArt(book, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (sequence.isNotEmpty()) {
                Text("#$sequence", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (entry.isCurrent) {
            Text("This book", style = MaterialTheme.typography.labelMedium, color = AmberGold)
        }
    }
}
