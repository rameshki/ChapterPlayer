package com.chapterplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.ui.components.BookCoverPlaceholder
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import com.chapterplayer.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/** The full player for whatever is playing. */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = koinViewModel<PlayerViewModel>()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlayerContent(
        state = state,
        onBack = onBack,
        onPlayPause = viewModel::playPause,
        onSkipBack = viewModel::skipBack,
        onSkipForward = viewModel::skipForward,
        onPreviousChapter = viewModel::previousChapter,
        onNextChapter = viewModel::nextChapter,
        onSeekBook = viewModel::seekBook,
        onSeekChapter = viewModel::seekChapter,
        onJumpToChapter = viewModel::jumpToChapter,
        onSetSpeed = viewModel::setSpeed,
        onSetSleepTimer = viewModel::setSleepTimer,
        onDismissError = viewModel::dismissError
    )
}

private const val SHEET_NONE = 0
private const val SHEET_SPEED = 1
private const val SHEET_SLEEP = 2
private const val SHEET_CHAPTERS = 3

/** The screen from state and callbacks only. */
@Composable
fun PlayerContent(
    state: PlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekBook: (Float) -> Unit,
    onSeekChapter: (Float) -> Unit,
    onJumpToChapter: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSleepTimer: (SleepTimer) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sheet by rememberSaveable { mutableStateOf(SHEET_NONE) }

    if (state.nothingPlaying) {
        NothingPlaying(onBack, modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize().background(BackgroundDeep)) {
        state.error?.let { ErrorBanner(it, onDismissError) }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlayerTopBar(state.title, onBack)
            Spacer(Modifier.height(8.dp))
            Cover(state)
            Spacer(Modifier.height(16.dp))
            ChapterHeading(state) { sheet = SHEET_CHAPTERS }
            if (state.author.isNotBlank()) {
                Text(
                    text = state.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            val chapterIndex = state.currentChapterIndex
            val chapterDuration = chapterIndex?.let { state.chapters.getOrNull(it)?.durationMs } ?: 0L
            if (state.chapterFraction != null) {
                SeekSlider(
                    name = "Chapter progress",
                    fraction = state.chapterFraction,
                    totalMs = chapterDuration,
                    elapsed = state.chapterElapsed.orEmpty(),
                    remaining = state.chapterRemaining.orEmpty(),
                    onSeek = onSeekChapter
                )
                Spacer(Modifier.height(8.dp))
            }
            SeekSlider(
                name = "Book progress",
                fraction = state.bookFraction,
                totalMs = state.durationMs,
                elapsed = state.elapsed,
                remaining = state.remaining,
                onSeek = onSeekBook
            )
            if (state.timeLeft.isNotBlank()) {
                Text(
                    text = state.timeLeft,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            TransportRow(state, onPreviousChapter, onSkipBack, onPlayPause, onSkipForward, onNextChapter)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Default.Speed, state.speedLabel, "Playback speed", Modifier.weight(1f)) { sheet = SHEET_SPEED }
                ToolButton(Icons.Default.Bedtime, state.sleepLabel, "Sleep timer", Modifier.weight(1f)) { sheet = SHEET_SLEEP }
                ToolButton(Icons.AutoMirrored.Filled.List, "Chapters", "Chapters", Modifier.weight(1f)) { sheet = SHEET_CHAPTERS }
            }
        }
    }

    when (sheet) {
        SHEET_SPEED -> SpeedSheet(state.speed, onSetSpeed) { sheet = SHEET_NONE }
        SHEET_SLEEP -> SleepTimerSheet(state.sleepTimer, state.sleepLabel, onSetSleepTimer) { sheet = SHEET_NONE }
        SHEET_CHAPTERS -> ChapterListSheet(state.chapters, state.currentChapterIndex, onJumpToChapter) { sheet = SHEET_NONE }
    }
}

@Composable
private fun NothingPlaying(onBack: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(BackgroundDeep).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Nothing is playing",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text("Back") }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceElevated)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Dismiss", color = TextSecondary)
        }
    }
}

@Composable
private fun PlayerTopBar(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        // Keeps the title centred against the back arrow.
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun Cover(state: PlayerUiState) {
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .clearAndSetSemantics { contentDescription = "Cover art for ${state.title}" }
    ) {
        if (state.coverUrl != null) {
            AsyncImage(
                model = state.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BookCoverPlaceholder(title = state.title, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The chapter title opens the chapter list; a book without chapters shows its own title and no chevron. */
@Composable
private fun ChapterHeading(state: PlayerUiState, onClick: () -> Unit) {
    val hasChapters = state.chapters.isNotEmpty()
    val text = if (hasChapters) state.chapterTitle ?: state.title else state.title
    val titleModifier = if (hasChapters) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Open chapter list", role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier = titleModifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() }
        )
        if (hasChapters) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextSecondary)
        }
    }
}

/**
 * A progress slider. The thumb follows [fraction] except while dragging; the seek happens on release,
 * and the dragged value stays until playback catches up (or a short timeout), so the thumb never jumps back.
 */
@Composable
private fun SeekSlider(
    name: String,
    fraction: Float,
    totalMs: Long,
    elapsed: String,
    remaining: String,
    onSeek: (Float) -> Unit
) {
    val enabled = slidersEnabled(totalMs)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var releaseCount by remember { mutableStateOf(0) }

    // After a release, the thumb returns to the state once it has caught up.
    val held = dragValue
    if (held != null && !dragging && seekSettled(held, fraction, totalMs)) dragValue = null
    LaunchedEffect(releaseCount) {
        if (releaseCount > 0) {
            delay(2_000)
            if (!dragging) dragValue = null
        }
    }

    val shown = (dragValue ?: fraction).coerceIn(0f, 1f)
    val labels = if (dragValue != null) dragLabels(shown, totalMs) else SliderLabels(elapsed, remaining)
    val percent = (shown * 100f).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                dragValue?.let(onSeek)
                releaseCount++
            },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = AmberGold,
                activeTrackColor = AmberGold,
                inactiveTrackColor = TextTertiary.copy(alpha = 0.35f),
                disabledThumbColor = TextTertiary,
                disabledActiveTrackColor = TextTertiary,
                disabledInactiveTrackColor = TextTertiary.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = name
                    stateDescription = "$percent percent, ${labels.elapsed} played, ${labels.remaining} left"
                }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(labels.elapsed, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("-${labels.remaining}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun TransportRow(
    state: PlayerUiState,
    onPreviousChapter: () -> Unit,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPreviousChapter, enabled = state.canGoPrevious, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter", modifier = Modifier.size(32.dp))
        }
        SkipButton(seconds = 15, forward = false, description = "Back 15 seconds", onClick = onSkipBack)
        PlayPauseButton(state.showPause, state.isBuffering, onPlayPause)
        SkipButton(seconds = 30, forward = true, description = "Forward 30 seconds", onClick = onSkipForward)
        IconButton(onClick = onNextChapter, enabled = state.canGoNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(32.dp))
        }
    }
}

/** A circular arrow with the number of seconds drawn on it; the forward one is the mirrored arrow. */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, description: String, onClick: () -> Unit) {
    // The number ignores the font scale: it has to fit inside the arrow.
    val numberSize = with(LocalDensity.current) { 10.dp.toSp() }
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Replay,
                contentDescription = description,
                modifier = Modifier.size(40.dp).scale(if (forward) -1f else 1f, 1f)
            )
            Text(
                text = "$seconds",
                fontSize = numberSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = AmberGold,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(36.dp)
            )
        }
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp).clearAndSetSemantics {},
                color = AmberGold,
                strokeWidth = 3.dp
            )
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = AmberGold)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
