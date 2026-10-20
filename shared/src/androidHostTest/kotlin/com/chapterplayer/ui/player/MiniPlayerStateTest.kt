package com.chapterplayer.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerStateTest {

    @Test
    fun `the chapter is the second line, else the author`() {
        val withChapter = PlayerUiState(nothingPlaying = false, title = "Book", author = "Ann", chapterTitle = "Chapter 2")
        assertEquals("Chapter 2", withChapter.toMiniPlayer().subtitle)
        val noChapter = PlayerUiState(nothingPlaying = false, title = "Book", author = "Ann")
        assertEquals("Ann", noChapter.toMiniPlayer().subtitle)
        assertEquals("", PlayerUiState(nothingPlaying = false, title = "Book").toMiniPlayer().subtitle)
    }

    @Test
    fun `fraction is clamped and pause follows showPause`() {
        val s = PlayerUiState(nothingPlaying = false, title = "B", bookFraction = 1.4f, showPause = true, coverUrl = "u")
        val m = s.toMiniPlayer()
        assertEquals(1f, m.fraction)
        assertEquals(true, m.showPause)
        assertEquals("u", m.coverUrl)
        assertEquals(0f, PlayerUiState(bookFraction = Float.NaN).toMiniPlayer().fraction)
    }
}
