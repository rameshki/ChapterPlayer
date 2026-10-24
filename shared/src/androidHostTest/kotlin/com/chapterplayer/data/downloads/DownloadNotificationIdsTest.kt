package com.chapterplayer.data.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNotificationIdsTest {

    @Test
    fun `each book keeps one id and no two books share one, even when their hashes collide`() {
        // "Aa" and "BB" have the same String.hashCode().
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val books = listOf("Aa", "BB") + (1..500).map { "abs_li_$it" }

        val ids = books.map { DownloadNotificationIds.idFor(it) }

        assertEquals(books.size, ids.toSet().size)
        assertEquals(ids, books.map { DownloadNotificationIds.idFor(it) })
    }
}
