package com.chapterplayer.domain.playback

import kotlin.math.floor

/** What one of the player's tool buttons (icon and a one or two word label such as "Bookmarks") needs in width, in dp, at normal text. */
const val TOOL_BUTTON_MIN_DP = 68f

/**
 * How many of the player's five tool buttons (speed, sleep, bookmarks, history, up next) share a row in [widthDp]: all five
 * when each gets [TOOL_BUTTON_MIN_DP] at the [fontScale] in use, else three (two rows of 3 and 2), else two. A label
 * then wraps between words and never in the middle of one ("Bookmark" and "s", found by the player screenshots).
 */
fun toolsPerRow(widthDp: Float, fontScale: Float): Int {
    val need = TOOL_BUTTON_MIN_DP * fontScale.coerceAtLeast(1f)
    val fit = floor(widthDp / need).toInt()
    return when {
        fit >= 5 -> 5
        fit >= 3 -> 3
        else -> 2
    }
}
