package com.chapterplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.chapterplayer.ui.components.BookCoverPlaceholder
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.Divider
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextPrimary
import com.chapterplayer.ui.theme.TextSecondary

/** What the mini player shows, taken from the full player's state so both stay in step. */
data class MiniPlayerState(
    val title: String,
    /** The chapter, else the author; empty when neither is known. */
    val subtitle: String,
    val coverUrl: String?,
    val fraction: Float,
    val showPause: Boolean,
)

fun PlayerUiState.toMiniPlayer() = MiniPlayerState(
    title = title,
    subtitle = chapterTitle ?: author,
    coverUrl = coverUrl,
    fraction = if (bookFraction.isNaN()) 0f else bookFraction.coerceIn(0f, 1f),
    showPause = showPause,
)

/** Shares the player's view model: one playback state source, no second controller connection. */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MiniPlayerContent(state.toMiniPlayer(), onOpenPlayer, viewModel::playPause, modifier)
}

@Composable
fun MiniPlayerContent(
    state: MiniPlayerState,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceElevated)
    ) {
        // At least 64dp, taller when large text needs it; the content stays centred.
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onOpenPlayer)
                    .clearAndSetSemantics {
                        contentDescription = "Now playing: ${state.title}. Open player"
                        role = Role.Button
                        onClick { onOpenPlayer(); true }
                    }
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                    if (state.coverUrl != null) {
                        AsyncImage(
                            model = state.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        BookCoverPlaceholder(title = "", modifier = Modifier.fillMaxSize())
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    Column {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.subtitle.isNotEmpty()) {
                            Text(
                                text = state.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (state.showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.showPause) "Pause" else "Play",
                    tint = AmberGold,
                    modifier = Modifier.size(28.dp)
                )
            }
            Box(modifier = Modifier.size(8.dp))
        }
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .clearAndSetSemantics { },
            color = AmberGold,
            trackColor = Divider,
            gapSize = 0.dp,
            drawStopIndicator = {}
        )
    }
}
