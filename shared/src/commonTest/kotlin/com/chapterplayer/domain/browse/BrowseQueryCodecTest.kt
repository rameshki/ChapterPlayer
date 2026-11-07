package com.chapterplayer.domain.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowseQueryCodecTest {

    private val full = BrowseQuery(
        progress = ProgressFilter.IN_PROGRESS,
        download = DownloadFilter.DOWNLOADED,
        sort = BrowseSort.AUTHOR,
        serverIds = setOf("server-a", "server-b"),
        favouritesOnly = true,
    )

    @Test
    fun `every field survives a round trip`() {
        assertEquals(full, decodeBrowseQuery(encodeBrowseQuery(full)))
    }

    @Test
    fun `the default query survives a round trip`() {
        assertEquals(BrowseQuery(), decodeBrowseQuery(encodeBrowseQuery(BrowseQuery())))
    }

    @Test
    fun `the stored form carries a version`() {
        assertTrue(encodeBrowseQuery(BrowseQuery()).contains("\"v\":$BROWSE_QUERY_FORMAT_VERSION"))
    }

    @Test
    fun `nothing stored reads as the default query`() {
        assertEquals(BrowseQuery(), decodeBrowseQuery(null))
        assertEquals(BrowseQuery(), decodeBrowseQuery(""))
        assertEquals(BrowseQuery(), decodeBrowseQuery("   "))
    }

    @Test
    fun `garbage reads as the default query`() {
        for (garbage in listOf("{", "not json", "[1,2,3]", "42", "\"text\"", "null", "{\"v\":", "\u0000\u0001")) {
            assertEquals(BrowseQuery(), decodeBrowseQuery(garbage), "for $garbage")
        }
    }

    @Test
    fun `an unknown or removed value falls back to that field's default and keeps the rest`() {
        val stored = """{"v":1,"progress":"HALF_HEARD","download":"DOWNLOADED","sort":"BY_MOOD","servers":["s1","s2"],"favouritesOnly":true}"""
        assertEquals(
            BrowseQuery(download = DownloadFilter.DOWNLOADED, serverIds = setOf("s1", "s2"), favouritesOnly = true),
            decodeBrowseQuery(stored)
        )
    }

    @Test
    fun `fields of the wrong type fall back to their defaults`() {
        val stored = """{"v":1,"progress":3,"download":["ALL"],"sort":{"x":1},"servers":"s1","favouritesOnly":"yes"}"""
        assertEquals(BrowseQuery(), decodeBrowseQuery(stored))
    }

    @Test
    fun `server ids that are not strings or are blank are left out`() {
        val stored = """{"v":1,"servers":["s1",7,null,"",{"id":"x"},"s2"]}"""
        assertEquals(setOf("s1", "s2"), decodeBrowseQuery(stored).serverIds)
    }

    @Test
    fun `unknown fields are ignored`() {
        val stored = """{"v":1,"sort":"TITLE","search":"some text","layout":"LIST"}"""
        assertEquals(BrowseQuery(sort = BrowseSort.TITLE), decodeBrowseQuery(stored))
    }

    @Test
    fun `a newer version is read as far as its fields are known`() {
        val stored = """{"v":99,"sort":"LENGTH","favouritesOnly":true,"newThing":[1]}"""
        assertEquals(BrowseQuery(sort = BrowseSort.LENGTH, favouritesOnly = true), decodeBrowseQuery(stored))
    }

    @Test
    fun `a stored form without a version is still read`() {
        assertEquals(BrowseQuery(progress = ProgressFilter.FINISHED), decodeBrowseQuery("""{"progress":"FINISHED"}"""))
    }
}
