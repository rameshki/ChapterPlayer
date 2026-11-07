package com.chapterplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** A short tick, for example when the A to Z scroller passes a letter. */
fun interface Haptics {
    fun tick()

    /** A long press was recognised (a book's long-press menu opens); a tick where nothing stronger is provided. */
    fun longPress() = tick()
}

/** Does nothing; the default where no haptics are provided (tests, previews). */
val NoHaptics: Haptics = Haptics { }

/** The app's haptics; the navigation host provides [rememberComposeHaptics]. */
val LocalHaptics = staticCompositionLocalOf { NoHaptics }

/** Haptics through Compose's own feedback interface, which each platform implements. */
@Composable
fun rememberComposeHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) {
        object : Haptics {
            override fun tick() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            override fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}
