package com.chapterplayer.data.local.entity

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a stored book keeps when it goes into Room and comes back. */
class BookEntityTest {

    private fun book(seriesName: String? = "Fixture Series", seriesSequence: String? = "3.5") = AudioBook(
        id = "abs_a", title = "T", author = "A", narrator = "N", durationMs = 1_000, chapters = emptyList(),
        streamUrl = "http://s.test/play", coverUrl = null, source = ServerSource.ABS, serverId = "srv1",
        libraryId = "lib1", addedAt = 1_700_000_000_000L, seriesName = seriesName, seriesSequence = seriesSequence,
    )

    @Test
    fun `a round trip keeps the library, added date and series`() {
        val back = BookEntity.fromDomain(book(), cachedAt = 5).toDomain(emptyList())

        assertEquals(book(), back)
        assertEquals("lib1", back.libraryId)
        assertEquals(1_700_000_000_000L, back.addedAt)
        assertEquals("Fixture Series", back.seriesName)
        assertEquals("3.5", back.seriesSequence)
    }

    @Test
    fun `a round trip keeps a missing series missing`() {
        val back = BookEntity.fromDomain(book(seriesName = null, seriesSequence = null), cachedAt = 5).toDomain(emptyList())

        assertNull(back.seriesName)
        assertNull(back.seriesSequence)
    }

    @Test
    fun `a series without a sequence keeps its name`() {
        val back = BookEntity.fromDomain(book(seriesSequence = null), cachedAt = 5).toDomain(emptyList())

        assertEquals("Fixture Series", back.seriesName)
        assertNull(back.seriesSequence)
    }
}
