package com.chapterplayer.domain.jellyfin

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JellyfinPlaybackTest {

    @Test
    fun `containers map to their media types`() {
        for (c in listOf("m4b", "m4a", "mp4", "aac", "M4B")) assertEquals("audio/mp4", jellyfinMimeType(c), c)
        assertEquals("audio/mpeg", jellyfinMimeType("mp3"))
        assertEquals("audio/flac", jellyfinMimeType("flac"))
        for (c in listOf("ogg", "oga", "opus")) assertEquals("audio/ogg", jellyfinMimeType(c), c)
        assertEquals("audio/wav", jellyfinMimeType("wav"))
        assertEquals("", jellyfinMimeType("wma"))
        assertEquals("", jellyfinMimeType(null))
    }

    @Test
    fun `an unknown container is unsupported`() {
        for (c in listOf("m4b", "mp3", "flac", "ogg", "opus", "wav", "aac")) assertTrue(jellyfinContainerSupported(c), c)
        assertFalse(jellyfinContainerSupported("wma"))
        assertFalse(jellyfinContainerSupported(""))
        assertFalse(jellyfinContainerSupported(null))
    }

    @Test
    fun `stream and cover addresses under a path prefix`() {
        for (base in listOf("https://jf.test/jellyfin", "https://jf.test/jellyfin/")) {
            assertEquals(
                "https://jf.test/jellyfin/Audio/abc/stream?static=true&mediaSourceId=def&playSessionId=0123",
                jellyfinStreamUrl(base, "abc", "def", "0123"),
            )
            assertEquals("https://jf.test/jellyfin/Items/abc/Images/Primary?tag=t1&maxWidth=600", jellyfinCoverUrl(base, "abc", "t1"))
        }
    }

    @Test
    fun `the stream address never carries a token`() {
        val url = jellyfinStreamUrl("https://jf.test", "abc", "def", newPlaySessionId())
        for (name in listOf("Token", "api_key", "ApiKey")) assertFalse(url.contains(name, ignoreCase = true), url)
    }

    @Test
    fun `two session ids differ and are 32 hex`() {
        val one = newPlaySessionId()
        val two = newPlaySessionId()
        assertNotEquals(one, two)
        for (id in listOf(one, two)) {
            assertEquals(32, id.length)
            assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' }, id)
        }
        assertEquals(newPlaySessionId(Random(7)), newPlaySessionId(Random(7)))
    }
}
