package com.chapterplayer.ui.details

import com.chapterplayer.domain.browse.PersonLink
import com.chapterplayer.domain.browse.SeriesLink
import com.chapterplayer.navigation.Screen
import com.chapterplayer.ui.library.decodeGroupKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailsTextTest {
    @Test
    fun `series line shows the number only when there is one`() {
        assertEquals("Part of Saga #2", seriesLineText(SeriesLink("saga", "Saga", "2", 3)))
        assertEquals("Part of Saga #3.5", seriesLineText(SeriesLink("saga", "Saga", " 3.5 ", 3)))
        assertEquals("Part of Saga", seriesLineText(SeriesLink("saga", "Saga", null, 3)))
        assertEquals("Part of Saga", seriesLineText(SeriesLink("saga", "Saga", "  ", 3)))
    }

    @Test
    fun `more by rows use singular and plural`() {
        assertEquals("More by Amy · 1 book", moreByText(PersonLink("amy", "Amy", 1), asNarrator = false))
        assertEquals("More by Amy · 3 books", moreByText(PersonLink("amy", "Amy", 3), asNarrator = false))
        assertEquals("More by Nia as narrator · 2 books", moreByText(PersonLink("nia", "Nia", 2), asNarrator = true))
        assertEquals("More by Nia as narrator · 1 book", moreByText(PersonLink("nia", "Nia", 1), asNarrator = true))
    }

    @Test
    fun `series tab is shown only with neighbours and a stale selection falls back`() {
        assertEquals(listOf(DetailsTab.ABOUT, DetailsTab.CHAPTERS), visibleTabs(false))
        assertEquals(listOf(DetailsTab.ABOUT, DetailsTab.CHAPTERS, DetailsTab.SERIES), visibleTabs(true))
        assertEquals(DetailsTab.CHAPTERS, effectiveTab(DetailsTab.SERIES, hasSeries = false))
        assertEquals(DetailsTab.SERIES, effectiveTab(DetailsTab.SERIES, hasSeries = true))
        assertEquals(DetailsTab.ABOUT, effectiveTab(DetailsTab.ABOUT, hasSeries = false))
    }

    @Test
    fun `length line joins what exists`() {
        assertEquals("10 h 5 min · 3 h 10 min left", lengthLine("10 h 5 min", "3 h 10 min left"))
        assertEquals("10 h 5 min · Finished", lengthLine("10 h 5 min", "Finished"))
        assertEquals("10 h 5 min", lengthLine("10 h 5 min", null))
        assertEquals("3 h left", lengthLine("", "3 h left"))
        assertNull(lengthLine("", null))
    }

    @Test
    fun `a book id survives the trip through a route`() {
        listOf("li_abc123", "a/b c", "Ünï cödé & more?#%/ ", "x_y_z").forEach { id ->
            val route = Screen.BookDetails.createRoute(id)
            val encoded = route.removePrefix("book/")
            assertTrue(encoded.all { it in '0'..'9' || it in 'a'..'f' }, "only hex characters: $encoded")
            assertEquals(id, decodeGroupKey(encoded))
        }
    }

    @Test
    fun `the same book on top is not opened again`() {
        val route = Screen.BookDetails.route
        val enc = Screen.BookDetails.createRoute("li_a/b").removePrefix("book/")
        assertTrue(Screen.BookDetails.isShowing(route, enc, "li_a/b"))
        assertFalse(Screen.BookDetails.isShowing(route, enc, "li_other"))
        assertFalse(Screen.BookDetails.isShowing("home", enc, "li_a/b"))
        assertFalse(Screen.BookDetails.isShowing(route, "zz", "li_a/b"))
        assertFalse(Screen.BookDetails.isShowing(route, null, "li_a/b"))
    }

    @Test
    fun `an empty chapter list says loading, needs a connection or none by what happened`() {
        assertEquals("Loading chapters...", emptyChaptersText(detailsLoaded = false, loadingDetails = true))
        assertEquals("Chapters need a connection", emptyChaptersText(detailsLoaded = false, loadingDetails = false))
        assertEquals("No chapters in this book", emptyChaptersText(detailsLoaded = true, loadingDetails = false))
        assertEquals("No chapters in this book", emptyChaptersText(detailsLoaded = true, loadingDetails = true))
    }

    @Test
    fun `more by links are capped to three with a toggle`() {
        val five = listOf(1, 2, 3, 4, 5)
        assertEquals(listOf(1, 2, 3), cappedLinks(five, expanded = false))
        assertEquals(five, cappedLinks(five, expanded = true))
        assertEquals(listOf(1, 2, 3), cappedLinks(listOf(1, 2, 3), expanded = false))
        assertEquals("Show all (5)", linksToggleLabel(5, expanded = false))
        assertEquals("Show less", linksToggleLabel(5, expanded = true))
        assertNull(linksToggleLabel(3, expanded = false))
        assertNull(linksToggleLabel(0, expanded = true))
    }

    @Test
    fun `the play button label follows the state and the saved progress`() {
        assertEquals("Resume", playButtonLabel(DetailsPlayback.NOT_PLAYING, hasProgress = true))
        assertEquals("Play", playButtonLabel(DetailsPlayback.NOT_PLAYING, hasProgress = false))
        assertEquals("Pause", playButtonLabel(DetailsPlayback.PLAYING, hasProgress = true))
        assertEquals("Pause", playButtonLabel(DetailsPlayback.PLAYING, hasProgress = false))
        assertEquals("Resume", playButtonLabel(DetailsPlayback.PAUSED, hasProgress = false))
        assertEquals("Resume", playButtonLabel(DetailsPlayback.PAUSED, hasProgress = true))
        assertEquals("Resume", playButtonLabel(DetailsPlayback.STARTING, hasProgress = true))
        assertEquals("Play", playButtonLabel(DetailsPlayback.STARTING, hasProgress = false))
    }

    @Test
    fun `the play button is enabled by state and permission`() {
        assertTrue(playButtonEnabled(DetailsPlayback.NOT_PLAYING, canPlay = true))
        assertFalse(playButtonEnabled(DetailsPlayback.NOT_PLAYING, canPlay = false))
        assertTrue(playButtonEnabled(DetailsPlayback.PAUSED, canPlay = true))
        assertFalse(playButtonEnabled(DetailsPlayback.PAUSED, canPlay = false))
        assertTrue(playButtonEnabled(DetailsPlayback.PLAYING, canPlay = false))
        assertFalse(playButtonEnabled(DetailsPlayback.STARTING, canPlay = true))
    }

    @Test
    fun `the caption shows the reason only when playing is not possible`() {
        assertEquals(NEEDS_CONNECTION_TEXT, playCaption(DetailsPlayback.NOT_PLAYING, false, NEEDS_CONNECTION_TEXT))
        assertNull(playCaption(DetailsPlayback.NOT_PLAYING, true, null))
        assertNull(playCaption(DetailsPlayback.PLAYING, false, NEEDS_CONNECTION_TEXT))
    }

    @Test
    fun `chapter taps and their note follow the same rule`() {
        assertTrue(chapterTapsEnabled(DetailsPlayback.NOT_PLAYING, canPlay = true))
        assertFalse(chapterTapsEnabled(DetailsPlayback.NOT_PLAYING, canPlay = false))
        assertTrue(chapterTapsEnabled(DetailsPlayback.PLAYING, canPlay = false))
        assertTrue(chapterTapsEnabled(DetailsPlayback.PAUSED, canPlay = false))
        assertFalse(chapterTapsEnabled(DetailsPlayback.STARTING, canPlay = true))
        assertEquals("Tap a chapter to jump to it", chaptersNote(true))
        assertNull(chaptersNote(false))
    }

    @Test
    fun `only one error banner at a time, the play error wins`() {
        assertEquals(null, detailsErrorToShow("Sign in again in Settings", "Could not load details."))
        assertEquals("Could not load details.", detailsErrorToShow(null, "Could not load details."))
        assertEquals(null, detailsErrorToShow(null, null))
        assertEquals(null, detailsErrorToShow("Play failed", null))
    }
}
