package com.chapterplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** The name of the book long-press menu for accessibility services (TalkBack's actions, VoiceOver's rotor). */
const val BOOK_MORE_OPTIONS = "More options"

/**
 * A book tile or row: a tap does [onClick] as before; a long press (with a haptic tick) or the "More options"
 * accessibility action opens the book's long-press menu through [onLongClick]. Without [onLongClick] it is a plain click.
 */
@Composable
fun Modifier.bookPress(onClick: () -> Unit, onLongClick: (() -> Unit)?, onClickLabel: String? = null): Modifier {
    if (onLongClick == null) return clickable(onClickLabel = onClickLabel, onClick = onClick)
    val haptics = LocalHaptics.current
    return semantics {
        customActions = listOf(CustomAccessibilityAction(BOOK_MORE_OPTIONS) { onLongClick(); true })
    }.combinedClickable(
        onClickLabel = onClickLabel,
        onLongClickLabel = BOOK_MORE_OPTIONS,
        onLongClick = {
            haptics.longPress()
            onLongClick()
        },
        hapticFeedbackEnabled = false,
        onClick = onClick,
    )
}
