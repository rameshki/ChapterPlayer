package com.chapterplayer.domain.browse

import com.chapterplayer.data.settings.LibraryLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryGridTest {
    @Test
    fun `phones keep two large and three small tiles across`() {
        for (width in listOf(320f, 360f, 411f, 430f)) {
            assertEquals(2, libraryGridColumns(LibraryLayout.LARGE_GRID, width), "large at $width")
            assertEquals(3, libraryGridColumns(LibraryLayout.SMALL_GRID, width), "small at $width")
        }
    }

    @Test
    fun `wide screens get more tiles so they keep their size`() {
        assertEquals(4, libraryGridColumns(LibraryLayout.LARGE_GRID, 800f))
        assertEquals(6, libraryGridColumns(LibraryLayout.LARGE_GRID, 1280f))
        assertEquals(6, libraryGridColumns(LibraryLayout.SMALL_GRID, 800f))
    }

    @Test
    fun `the list is one column`() {
        assertEquals(1, libraryGridColumns(LibraryLayout.LIST, 1280f))
    }
}
