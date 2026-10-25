package com.chapterplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.ui.theme.AbsGreen
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.AbsGreenDim
import com.chapterplayer.ui.theme.PlexPurpleActual
import com.chapterplayer.ui.theme.PlexPurpleDim

/** Whether source badges are shown at all: set by the app from the added servers (see `showSourceBadges`). */
val LocalShowSourceBadge = staticCompositionLocalOf { false }

/**
 * A small pill badge indicating the book's source server (ABS or Plex).
 */
@Composable
fun SourceBadge(
    source: ServerSource,
    modifier: Modifier = Modifier
) {
    if (!LocalShowSourceBadge.current) return
    val (label, textColor, bgColor) = when (source) {
        ServerSource.ABS -> Triple("ABS", AbsGreen, AbsGreenDim)
        ServerSource.PLEX -> Triple("PLEX", PlexPurpleActual, PlexPurpleDim)
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        modifier = modifier
            .background(
                color = bgColor.copy(alpha = 0.8f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * The mark on a book that several servers have ("2 sources"). It shows whatever servers are added (it is not a
 * source badge); [description] is what a screen reader says ("2 sources, available from Audiobookshelf and Plex").
 */
@Composable
fun SourcesPill(text: String, description: String?, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AmberGold,
        modifier = modifier
            .semantics { contentDescription = if (description != null) "$text, $description" else text }
            .background(color = SurfaceElevated.copy(alpha = 0.9f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
