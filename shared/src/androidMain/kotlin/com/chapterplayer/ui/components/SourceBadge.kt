package com.chapterplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.ui.theme.AbsGreen
import com.chapterplayer.ui.theme.AbsGreenDim
import com.chapterplayer.ui.theme.PlexPurpleActual
import com.chapterplayer.ui.theme.PlexPurpleDim

/**
 * A small pill badge indicating the book's source server (ABS or Plex).
 */
@Composable
fun SourceBadge(
    source: ServerSource,
    modifier: Modifier = Modifier
) {
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
