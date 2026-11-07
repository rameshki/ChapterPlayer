package com.chapterplayer.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt

/**
 * How far the keyboard reaches into a screen whose bottom edge lies [gapBelowPx] above the window's bottom
 * (the tab bar, the mini player or the navigation bar under it), with the keyboard [imeBottomPx] tall.
 */
internal fun keyboardOverlapPx(imeBottomPx: Int, gapBelowPx: Int): Int =
    (imeBottomPx - gapBelowPx.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Pads the bottom by the part of the keyboard that covers this element, nothing more. The app draws edge to edge,
 * so the keyboard no longer shrinks the window; plain `imePadding()` would also count the tab bar and navigation
 * bar the screen already sits above. Put it before a `verticalScroll` (or on a column holding a lazy list) so the
 * shrunk viewport keeps the focused field above the keyboard.
 */
fun Modifier.keyboardOverlapPadding(): Modifier = composed {
    val ime = WindowInsets.ime
    val gapBelow = remember { mutableIntStateOf(0) }
    this
        .onGloballyPositioned { coordinates ->
            val root = coordinates.findRootCoordinates()
            val bottom = root.localPositionOf(coordinates, Offset(0f, coordinates.size.height.toFloat())).y
            gapBelow.intValue = (root.size.height - bottom).roundToInt()
        }
        .layout { measurable, constraints ->
            val pad = keyboardOverlapPx(ime.getBottom(this), gapBelow.intValue)
                .coerceAtMost(if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE)
            val placeable = measurable.measure(constraints.offset(vertical = -pad))
            val height = (placeable.height + pad).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
}
