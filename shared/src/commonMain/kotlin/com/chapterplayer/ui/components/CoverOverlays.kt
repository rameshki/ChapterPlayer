package com.chapterplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.domain.browse.DownloadBadgeKind
import com.chapterplayer.domain.browse.coverOverlay
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary

/** A check on a finished book's cover. */
@Composable
fun FinishedCheck(modifier: Modifier = Modifier) = CheckDisc("Finished", modifier)

/** A thin amber bar along the bottom of a cover. */
@Composable
fun ReadingProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(3.dp),
        color = AmberGold,
        trackColor = SurfaceSecondary
    )
}

/**
 * Dims a cover and shows a cloud when the book cannot play right now. Put it as the last child
 * of the cover Box.
 */
@Composable
fun DimmedCoverOverlay(dimmed: Boolean, modifier: Modifier = Modifier) {
    if (!dimmed) return
    Box(
        modifier = modifier.fillMaxSize().background(SurfaceElevated.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = "Not available offline",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(28.dp)
        )
    }
}
