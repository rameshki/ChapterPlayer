package com.chapterplayer.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Hands text to the system share sheet so the person picks where it goes (the "Report a problem" report). */
fun interface TextSharer {
    fun share(title: String, text: String)
}

/** The platform's sharer, or null where none is provided (the caller then copies the text instead). */
val LocalTextSharer = staticCompositionLocalOf<TextSharer?> { null }
