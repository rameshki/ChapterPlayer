package com.chapterplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Favorite
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
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary

/** A check on a finished book's cover. */
@Composable
fun FinishedCheck(modifier: Modifier = Modifier) = CheckDisc("Finished", modifier)

/** The small heart of a favourite book: on a dark disc on covers, on its own in a row. */
@Composable
fun FavouriteMark(modifier: Modifier = Modifier, onCover: Boolean = true) {
    val disc = if (onCover) Modifier.background(SurfaceElevated.copy(alpha = 0.85f), CircleShape) else Modifier
    Box(modifier = modifier.size(20.dp).then(disc), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Favorite, contentDescription = "Favourite", tint = AmberGold, modifier = Modifier.size(12.dp))
    }
}

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

/**
 * The selection mode mark on a cover: a gold disc with a check and a gold wash over the cover when [selected], an empty
 * ring otherwise. Put it as the last child of the cover Box.
 */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(
        modifier = modifier.fillMaxSize().then(if (selected) Modifier.background(AmberGold.copy(alpha = 0.28f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(size).background(AmberGold, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = BOOK_SELECTED, tint = BackgroundDeep, modifier = Modifier.size(size * 0.65f))
            }
        } else {
            Box(
                Modifier.size(size)
                    .background(SurfaceElevated.copy(alpha = 0.55f), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f), CircleShape)
            )
        }
    }
}