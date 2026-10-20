package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class BookmarksTest {

    private fun bm(time: Long, title: String = "t", createdAt: Long = 1, state: BookmarkState = BookmarkState.SYNCED, book: String = "a") =
        Bookmark(book, time, title, createdAt, state)

    // ---- titles ----

    @Test
    fun `the default title names the clock time`() {
        assertEquals("Bookmark at 0:00", defaultBookmarkTitle(0))
        assertEquals("Bookmark at 1:02", defaultBookmarkTitle(62_500))
        assertEquals("Bookmark at 59:59", defaultBookmarkTitle(3_599_999))
        assertEquals("Bookmark at 1:02:03", defaultBookmarkTitle(3_723_000))
        assertEquals("Bookmark at 0:00", defaultBookmarkTitle(-5))
    }

    @Test
    fun `a typed name is trimmed and an empty or missing one falls back to the default`() {
        assertEquals("Chapter 2", bookmarkTitleOrDefault("  Chapter 2  ", 5_000))
        assertEquals("Bookmark at 0:05", bookmarkTitleOrDefault(null, 5_000))
        assertEquals("Bookmark at 0:05", bookmarkTitleOrDefault("", 5_000))
        assertEquals("Bookmark at 0:05", bookmarkTitleOrDefault("   ", 5_000))
    }

    // ---- visibleBookmarks ----

    @Test
    fun `visible bookmarks drop the ones waiting to be deleted and sort by time then creation`() {
        val all = listOf(
            bm(300, "c"),
            bm(100, "late", createdAt = 9),
            bm(100, "early", createdAt = 2),
            bm(200, "gone", state = BookmarkState.PENDING_DELETE),
        )
        assertEquals(listOf("early", "late", "c"), visibleBookmarks(all).map { it.title })
    }

    // ---- mergeBookmarks ----

    @Test
    fun `a server bookmark with no local one becomes a synced row`() {
        val merge = mergeBookmarks(emptyList(), listOf(bm(100, "s")))
        assertEquals(listOf(bm(100, "s")), merge.result)
        assertEquals(emptyList(), merge.toCreate)
        assertEquals(emptyList(), merge.toRename)
        assertEquals(emptyList(), merge.toDelete)
    }

    @Test
    fun `a synced local row is replaced by the server row`() {
        val merge = mergeBookmarks(listOf(bm(100, "old", createdAt = 1)), listOf(bm(100, "new", createdAt = 5)))
        assertEquals(listOf(bm(100, "new", createdAt = 5)), merge.result)
    }

    @Test
    fun `a pending create that the server already has becomes the server row and creates nothing`() {
        val merge = mergeBookmarks(listOf(bm(100, "mine", state = BookmarkState.PENDING_CREATE)), listOf(bm(100, "theirs")))
        assertEquals(listOf(bm(100, "theirs")), merge.result)
        assertEquals(emptyList(), merge.toCreate)
    }

    @Test
    fun `a pending rename stays and is sent`() {
        val local = bm(100, "renamed", state = BookmarkState.PENDING_RENAME)
        val merge = mergeBookmarks(listOf(local), listOf(bm(100, "server title")))
        assertEquals(listOf(local), merge.result)
        assertEquals(listOf(local), merge.toRename)
        assertEquals(emptyList(), merge.toCreate)
        assertEquals(emptyList(), merge.toDelete)
    }

    @Test
    fun `a pending delete stays and is sent`() {
        val local = bm(100, "x", state = BookmarkState.PENDING_DELETE)
        val merge = mergeBookmarks(listOf(local), listOf(bm(100, "x")))
        assertEquals(listOf(local), merge.result)
        assertEquals(listOf(local), merge.toDelete)
        assertEquals(emptyList(), merge.toRename)
    }

    @Test
    fun `a synced local row that the server no longer has is dropped`() {
        val merge = mergeBookmarks(listOf(bm(100, "x")), emptyList())
        assertEquals(emptyList(), merge.result)
    }

    @Test
    fun `a pending create that the server does not have stays and is created`() {
        val local = bm(100, "mine", state = BookmarkState.PENDING_CREATE)
        val merge = mergeBookmarks(listOf(local), emptyList())
        assertEquals(listOf(local), merge.result)
        assertEquals(listOf(local), merge.toCreate)
    }

    @Test
    fun `a pending rename or delete that the server no longer has is dropped`() {
        val merge = mergeBookmarks(
            listOf(
                bm(100, "r", state = BookmarkState.PENDING_RENAME),
                bm(200, "d", state = BookmarkState.PENDING_DELETE),
            ),
            emptyList()
        )
        assertEquals(emptyList(), merge.result)
        assertEquals(emptyList(), merge.toRename)
        assertEquals(emptyList(), merge.toDelete)
    }

    @Test
    fun `the merged result is sorted by time`() {
        val merge = mergeBookmarks(
            listOf(bm(300, "c", state = BookmarkState.PENDING_CREATE)),
            listOf(bm(200, "b"), bm(100, "a"))
        )
        assertEquals(listOf(100L, 200L, 300L), merge.result.map { it.timeSec })
    }
}
