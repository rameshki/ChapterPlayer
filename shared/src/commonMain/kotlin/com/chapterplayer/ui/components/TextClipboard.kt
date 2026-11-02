package com.chapterplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** Puts text on the system clipboard (the Diagnostics page's Copy report). It never reads the clipboard. */
fun interface TextClipboard {
    fun copy(text: String)
}

/** Does nothing; the default where no clipboard is provided (tests, previews). */
val NoTextClipboard: TextClipboard = TextClipboard { }

/** The app's clipboard; the navigation host provides [rememberComposeTextClipboard]. */
val LocalTextClipboard = staticCompositionLocalOf { NoTextClipboard }

/** Writes through Compose's clipboard, which each platform implements. */
@Suppress("DEPRECATION")
@Composable
fun rememberComposeTextClipboard(): TextClipboard {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { TextClipboard { text -> clipboard.setText(AnnotatedString(text)) } }
}
