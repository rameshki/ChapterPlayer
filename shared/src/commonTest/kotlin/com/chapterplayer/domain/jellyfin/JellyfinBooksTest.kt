package com.chapterplayer.domain.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinBooksTest {
    @Test
    fun `a book id is the prefix and the item id`() {
        assertEquals("jf_0a1b2c3d4e5f60718293a4b5c6d7e8f9", jellyfinBookId("0a1b2c3d4e5f60718293a4b5c6d7e8f9"))
    }

    @Test
    fun `the item id is read back from a Jellyfin book id`() {
        assertEquals("abc", jellyfinItemId("jf_abc"))
        assertTrue(isJellyfinBook("jf_abc"))
    }

    @Test
    fun `other ids are not Jellyfin books`() {
        assertNull(jellyfinItemId("plex_12"))
        assertNull(jellyfinItemId("jf_"))
        assertNull(jellyfinItemId("li_x"))
        assertFalse(isJellyfinBook("li_x"))
        assertFalse(isJellyfinBook("plex_12"))
    }
}
