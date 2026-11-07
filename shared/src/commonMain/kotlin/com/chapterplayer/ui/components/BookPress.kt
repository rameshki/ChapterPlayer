package com.chapterplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

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

/** What TalkBack and VoiceOver say for a tile in selection mode. */
const val BOOK_SELECTED = "Selected"
const val BOOK_NOT_SELECTED = "Not selected"

/**
 * A book tile or row in selection mode: a tap (or the accessibility click) selects or unselects it ([onToggle]). It is
 * a check box for accessibility services, with "Selected" / "Not selected" as its state.
 */
fun Modifier.selectionPress(selected: Boolean, onToggle: () -> Unit): Modifier =
    semantics { stateDescription = if (selected) BOOK_SELECTED else BOOK_NOT_SELECTED }
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })