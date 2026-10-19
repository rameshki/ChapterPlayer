package com.chapterplayer.ui.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryViewTest {

    @Test
    fun `each view has its own singular title for the group detail fallback`() {
        assertEquals("Author", LibraryView.AUTHORS.singularTitle)
        assertEquals("Narrator", LibraryView.NARRATORS.singularTitle)
        assertEquals("Series", LibraryView.SERIES.singularTitle)
        assertEquals("Collection", LibraryView.COLLECTIONS.singularTitle)
    }
}
