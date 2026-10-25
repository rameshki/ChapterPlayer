package com.chapterplayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chapterplayer.domain.browse.FAST_SCROLL_ALPHABET
import com.chapterplayer.domain.browse.LetterIndex
import com.chapterplayer.domain.browse.indexForLetter
import com.chapterplayer.domain.browse.letterAtFraction
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The visible strip. */
private val StripWidth = 24.dp

/** The touch area around it, wider than the strip so a thumb finds it; nothing left of it is touched. */
private val TouchWidth = 40.dp
private val BubbleSize = 64.dp
private val BubbleGap = 8.dp
private const val HIDE_DELAY_MS = 1_500L

/**
 * The A to Z strip on the right edge of a list, drawn over it (pass a modifier that fills the list's box).
 * Touching or dragging along the strip jumps the list to the letter under the finger at once, with a
 * bubble showing the letter and a haptic tick on each new letter. The strip shows while the list scrolls
 * or the strip is used and fades a moment after; it only takes touches while it shows, and only within
 * [TouchWidth] of the edge (a hidden strip has no touch handler, so the list edge stays tappable). Letters that no item starts with are dimmed and skipped.
 *
 * @param letters the index from the list as shown; empty draws nothing.
 * @param onScrollTo jumps the list to an item position (for example `LazyListState.scrollToItem`).
 * @param indexOffset items before the first indexed one (a count header, for example).
 * @param bottomPadding the list's own bottom padding, so the strip ends where the list's items do.
 */
@Composable
fun FastScroller(
    letters: List<LetterIndex>,
    isListScrolling: Boolean,
    onScrollTo: suspend (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    indexOffset: Int = 0,
    bottomPadding: Dp = 16.dp,
    haptics: Haptics = LocalHaptics.current
) {
    if (letters.isEmpty()) return
    val scope = rememberCoroutineScope()
    val currentLetters by rememberUpdatedState(letters)
    val currentOffset by rememberUpdatedState(indexOffset)
    val currentScroll by rememberUpdatedState(onScrollTo)
    val currentHaptics by rememberUpdatedState(haptics)

    var dragging by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LetterIndex?>(null) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var stripHeight by remember { mutableIntStateOf(0) }
    // Shown at first so the strip is found, then only while the list scrolls or the strip is in use.
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(isListScrolling, dragging) {
        if (isListScrolling || dragging) {
            shown = true
        } else {
            delay(HIDE_DELAY_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScrollAlpha")

    fun touchAt(y: Float) {
        touchY = y
        if (stripHeight <= 0) return
        val letter = letterAtFraction(currentLetters, y / stripHeight) ?: return
        if (letter != selected) {
            selected = letter
            currentHaptics.tick()
            scope.launch { currentScroll(letter.firstIndex + currentOffset) }
        }
    }

    val present = remember(letters) { letters.mapTo(mutableSetOf()) { it.letter } }
    val actions = remember(letters) {
        FAST_SCROLL_ALPHABET.filter { it in present }.map { letter ->
            CustomAccessibilityAction("Jump to $letter") {
                val index = indexForLetter(currentLetters, letter) ?: return@CustomAccessibilityAction false
                scope.launch { currentScroll(index + currentOffset) }
                true
            }
        }
    }

    val density = LocalDensity.current
    Box(modifier = modifier.padding(top = 8.dp, bottom = bottomPadding)) {
        // The bubble beside the finger, left of the touch area.
        val bubble = selected
        if (dragging && bubble != null) {
            val bubblePx = with(density) { BubbleSize.toPx() }
            val shiftPx = with(density) { (TouchWidth + BubbleGap).toPx() }
            val maxY = (stripHeight - bubblePx).coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-shiftPx.roundToInt(), (touchY - bubblePx / 2).coerceIn(0f, maxY).roundToInt()) }
                    .size(BubbleSize)
                    .clip(CircleShape)
                    .background(AmberGold),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = bubble.letter.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // The touch area: the strip plus a margin, as tall as the list.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(TouchWidth)
                .fillMaxHeight()
                .onSizeChanged { stripHeight = it.height }
                .semantics {
                    contentDescription = "Jump to letter"
                    customActions = actions
                }
                // A hidden strip has no touch handler at all, so the list underneath gets the touch.
                .then(if (shown) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        dragging = true
                        selected = null
                        touchAt(down.position.y)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                touchAt(change.position.y)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                } else Modifier)
        ) {
            val stripColor = SurfaceElevated
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(StripWidth)
                    .fillMaxHeight()
                    .alpha(alpha)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (dragging) stripColor else stripColor.copy(alpha = 0.7f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val onSurface = MaterialTheme.colorScheme.onSurface
                val dimmed = TextSecondary.copy(alpha = 0.4f)
                val highlight = AmberGold
                FAST_SCROLL_ALPHABET.forEach { letter ->
                    val isSelected = dragging && selected?.letter == letter
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = letter.toString(),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isSelected -> highlight
                                letter in present -> onSurface
                                else -> dimmed
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
