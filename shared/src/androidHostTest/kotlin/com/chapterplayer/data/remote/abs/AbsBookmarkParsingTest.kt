package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsBookmarkDto
import com.chapterplayer.data.remote.abs.dto.AbsMeBookmarksDto
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bookmark part of the me response, from the synthetic fixture. */
class AbsBookmarkParsingTest {

    @Test
    fun `the me response gives only the bookmark list and ignores everything else`() {
        val me = ApiJson.decodeFromString<AbsMeBookmarksDto>(fixture("abs/bookmarks.json"))

        assertEquals(3, me.bookmarks.size)
        assertEquals(listOf("li_fixture01", "li_fixture01", "li_fixture02"), me.bookmarks.map { it.libraryItemId })
        assertEquals("Start of chapter two", me.bookmarks[0].title)
        assertEquals(1760000100000L, me.bookmarks[0].createdAt)
    }

    @Test
    fun `a bookmark becomes a synced row with the app book id and whole seconds`() {
        val rows = ApiJson.decodeFromString<AbsMeBookmarksDto>(fixture("abs/bookmarks.json")).bookmarks.map { it.toBookmark() }

        assertEquals(Bookmark("abs_li_fixture01", 120, "Start of chapter two", 1760000100000L, BookmarkState.SYNCED), rows[0])
        assertEquals("abs_li_fixture02", rows[2].bookId)
        assertEquals(45L, rows[2].timeSec)
    }

    @Test
    fun `a fractional time is rounded to the nearest second`() {
        assertEquals(3723L, AbsBookmarkDto("x", time = 3723.4).toBookmark().timeSec)
        assertEquals(3724L, AbsBookmarkDto("x", time = 3723.6).toBookmark().timeSec)
        val fromFixture = ApiJson.decodeFromString<AbsMeBookmarksDto>(fixture("abs/bookmarks.json")).bookmarks[1].toBookmark()
        assertEquals(3723L, fromFixture.timeSec)
    }

    @Test
    fun `missing fields take their defaults and a missing list is empty`() {
        val dto = ApiJson.decodeFromString<AbsBookmarkDto>("""{"libraryItemId":"li_a"}""")

        assertEquals(Bookmark("abs_li_a", 0, "", 0, BookmarkState.SYNCED), dto.toBookmark())
        assertEquals(emptyList(), ApiJson.decodeFromString<AbsMeBookmarksDto>("""{"id":"u"}""").bookmarks)
    }
}
