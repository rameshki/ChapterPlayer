package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexBooksContainer
import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlexPagingTest {

    private val asked = mutableListOf<Pair<Int, Int>>()

    private fun page(keys: IntRange, total: Int?) = PlexLibraryAllResponse(
        PlexBooksContainer(size = keys.count(), totalSize = total, metadata = keys.map { PlexMetadataDto(ratingKey = "$it") })
    )

    /** A server holding [count] entries that honours paging. */
    private fun library(count: Int, sendTotal: Boolean = true): suspend (Int, Int) -> PlexLibraryAllResponse = { start, size ->
        asked += start to size
        page(start until minOf(start + size, count), if (sendTotal) count else null)
    }

    @Test
    fun `reads every page until the total`() = runTest {
        val items = fetchAllPlexItems(pageSize = 2, fetchPage = library(5))

        assertEquals((0 until 5).map { "$it" }, items.map { it.ratingKey })
        assertEquals(listOf(0 to 2, 2 to 2, 4 to 2), asked)
    }

    @Test
    fun `stops at the total even when the last page is full`() = runTest {
        fetchAllPlexItems(pageSize = 2, fetchPage = library(4))

        assertEquals(listOf(0 to 2, 2 to 2), asked)
    }

    @Test
    fun `a server that caps pages below the asked size is read to the total`() = runTest {
        // Asked for 200, the server sends at most 100 per page.
        val items = fetchAllPlexItems(pageSize = 200) { start, size ->
            asked += start to size
            page(start until minOf(start + 100, 250), total = 250)
        }

        assertEquals(250, items.size)
        assertEquals(listOf(0 to 200, 100 to 200, 200 to 200), asked)
    }

    @Test
    fun `an empty page before the total stops`() = runTest {
        val items = fetchAllPlexItems(pageSize = 2) { start, _ ->
            asked += start to 2
            if (start == 0) page(0..1, total = 10) else page(IntRange.EMPTY, total = 10)
        }

        assertEquals(2, items.size)
        assertEquals(2, asked.size)
    }

    @Test
    fun `without a total a short page is the last`() = runTest {
        val items = fetchAllPlexItems(pageSize = 2, fetchPage = library(3, sendTotal = false))

        assertEquals(3, items.size)
        assertEquals(listOf(0 to 2, 2 to 2), asked)
    }

    @Test
    fun `a server that ignores paging is read once more and then left`() = runTest {
        val items = fetchAllPlexItems(pageSize = 2) { start, size ->
            asked += start to size
            page(0 until 2, total = null)
        }

        assertEquals(2, items.size)
        assertEquals(2, asked.size)
    }

    @Test
    fun `an entry repeated on the next page is kept once`() = runTest {
        val items = fetchAllPlexItems(pageSize = 2) { start, _ ->
            if (start == 0) page(0..1, total = 3) else page(1..2, total = 3)
        }

        assertEquals(listOf("0", "1", "2"), items.map { it.ratingKey })
    }

    @Test
    fun `an empty section is one request`() = runTest {
        assertEquals(emptyList(), fetchAllPlexItems(pageSize = 2, fetchPage = library(0)))
        assertEquals(1, asked.size)
    }

    @Test
    fun `gives up after the page limit`() = runTest {
        var next = 0
        assertFailsWith<IllegalStateException> {
            fetchAllPlexItems(pageSize = 1, maxPages = 3) { _, _ -> page(next..next, total = null).also { next++ } }
        }
    }
}
