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
}
