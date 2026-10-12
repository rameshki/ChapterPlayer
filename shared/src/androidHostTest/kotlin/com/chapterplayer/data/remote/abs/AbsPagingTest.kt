package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemDto
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse
import com.chapterplayer.data.remote.abs.dto.AbsMediaDto
import com.chapterplayer.data.remote.abs.dto.AbsMetadataDto
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AbsPagingTest {

    private fun item(id: String) = AbsLibraryItemDto(
        id = id,
        media = AbsMediaDto(
            metadata = AbsMetadataDto(title = "Title $id", authorName = "Author", narratorName = ""),
            chapters = null,
            duration = 60.0,
            coverPath = null
        )
    )

    private fun response(ids: List<String>, total: Int?, page: Int, limit: Int) =
        AbsLibraryItemsResponse(results = ids.map(::item), total = total, limit = limit, page = page)

    /** A server holding [ids] that serves them page by page and records each request. */
    private class FakeLibrary(private val ids: List<String>) {
        val requests = mutableListOf<Pair<Int, Int>>()

        fun serve(page: Int, limit: Int): List<String> {
            requests += page to limit
            return ids.drop(page * limit).take(limit)
        }
    }

    private fun ids(count: Int) = (0 until count).map { "id$it" }

    @Test
    fun `fetches every page until the reported total is reached`() = runTest {
        val all = ids(250)
        val library = FakeLibrary(all)

        val items = fetchAllAbsItems(pageSize = 100) { page, limit ->
            response(library.serve(page, limit), total = all.size, page = page, limit = limit)
        }

        assertEquals(all, items.map { it.id })
    }

    @Test
    fun `makes a single request when the library fits in one page`() = runTest {
        val all = ids(40)
        val library = FakeLibrary(all)

        val items = fetchAllAbsItems(pageSize = 100) { page, limit ->
            response(library.serve(page, limit), total = all.size, page = page, limit = limit)
        }

        assertEquals(40, items.size)
        assertEquals(listOf(0 to 100), library.requests)
    }

    @Test
    fun `requests consecutive page numbers with the page size`() = runTest {
        val all = ids(250)
        val library = FakeLibrary(all)

        fetchAllAbsItems(pageSize = 100) { page, limit ->
            response(library.serve(page, limit), total = all.size, page = page, limit = limit)
        }

        assertEquals(listOf(0 to 100, 1 to 100, 2 to 100), library.requests)
    }

    @Test
    fun `stops when the server returns an empty page before the reported total`() = runTest {
        val all = ids(100)
        val library = FakeLibrary(all)

        // The server claims 500 items but only ever has 100.
        val items = fetchAllAbsItems(pageSize = 100) { page, limit ->
            response(library.serve(page, limit), total = 500, page = page, limit = limit)
        }

        assertEquals(100, items.size)
        assertEquals(listOf(0 to 100, 1 to 100), library.requests)
    }

    @Test
    fun `drops duplicates when items shift between pages and still collects the rest`() = runTest {
        val all = ids(150)

        // A book was added while paging, so page 1 starts with the last item of page 0 again.
        val items = fetchAllAbsItems(pageSize = 100) { page, limit ->
            when (page) {
                0 -> response(all.take(100), total = 150, page = 0, limit = limit)
                else -> response(all.drop(99), total = 150, page = 1, limit = limit)
            }
        }

        assertEquals(all, items.map { it.id })
    }

    @Test
    fun `fails instead of looping forever when the server never finishes`() = runTest {
        val endless = fun(page: Int): List<String> = (0 until 100).map { "p${page}i$it" }

        assertFailsWith<IllegalStateException> {
            fetchAllAbsItems(pageSize = 100, maxPages = 3) { page, limit ->
                response(endless(page), total = 1_000_000, page = page, limit = limit)
            }
        }
    }

    @Test
    fun `propagates an error from any page`() = runTest {
        val all = ids(250)
        val library = FakeLibrary(all)

        assertFailsWith<IOException> {
            fetchAllAbsItems(pageSize = 100) { page, limit ->
                if (page == 1) throw IOException("connection reset")
                response(library.serve(page, limit), total = all.size, page = page, limit = limit)
            }
        }
    }
    @Test
    fun `keeps paging when some pages omit the total`() = runTest {
        val all = ids(250)
        val library = FakeLibrary(all)

        // Real servers send some pages without a total, so only page 0 and 2 report it.
        val items = fetchAllAbsItems(pageSize = 100) { page, limit ->
            val total = if (page == 1) null else all.size
            response(library.serve(page, limit), total = total, page = page, limit = limit)
        }

        assertEquals(all, items.map { it.id })
    }

    @Test
    fun `stops on a short page when the total is never reported`() = runTest {
        val all = ids(250)
        val library = FakeLibrary(all)

        val items = fetchAllAbsItems(pageSize = 100) { page, limit ->
            response(library.serve(page, limit), total = null, page = page, limit = limit)
        }

        assertEquals(all, items.map { it.id })
        assertEquals(listOf(0 to 100, 1 to 100, 2 to 100), library.requests)
    }
}
