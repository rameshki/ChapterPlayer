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

/** A small mark for where a book's audio is. Draws nothing for a book that is not downloaded. */
@Composable
fun DownloadBadge(state: DownloadState, modifier: Modifier = Modifier) {
    when (coverOverlay(null, state, false).badge) {
        DownloadBadgeKind.NONE -> Unit
        DownloadBadgeKind.DOWNLOADED -> CheckDisc("Downloaded", modifier)
        DownloadBadgeKind.DOWNLOADING -> Box(
            modifier = modifier
                .size(20.dp)
                .background(SurfaceElevated, CircleShape)
                .padding(3.dp)
                .semantics { contentDescription = "Downloading" }
        ) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = AmberGold,
                trackColor = SurfaceSecondary,
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
internal fun CheckDisc(description: String, modifier: Modifier) {
    Box(
        modifier = modifier.size(20.dp).background(AmberGold, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp)
        )
    }
}
