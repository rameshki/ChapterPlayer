package com.chapterplayer.domain.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JellyfinAuthTest {

    @Test
    fun `header without token`() {
        assertEquals(
            "MediaBrowser Client=\"ChapterPlayer\", Device=\"Android\", DeviceId=\"chapterplayer-i1-s1\", Version=\"0.4.1\"",
            jellyfinAuthHeader(JellyfinClient("Android", "chapterplayer-i1-s1", "0.4.1"), null),
        )
    }

    @Test
    fun `header with a token ends with it`() {
        assertEquals(
            "MediaBrowser Client=\"ChapterPlayer\", Device=\"iPhone\", DeviceId=\"chapterplayer-i1-s1\", Version=\"0.4.1\", " +
                "Token=\"0123abcdef\"",
            jellyfinAuthHeader(JellyfinClient("iPhone", "chapterplayer-i1-s1", "0.4.1"), "0123abcdef"),
        )
    }

    @Test
    fun `a blank token is left out`() {
        assertFalse(jellyfinAuthHeader(JellyfinClient("Android", "d", "1"), "").contains("Token="))
    }

    @Test
    fun `header escapes token and client fields`() {
        val client = JellyfinClient("Android", "chapterplayer-i1-s1", "0.4.1 (debug)")
        val withToken = jellyfinAuthHeader(client, "a+b%c\"d")
        assertTrue(withToken.contains("Token=\"a%2Bb%25c%22d\""), withToken)
        assertTrue(withToken.contains("Version=\"0.4.1 debug\""), withToken)
        assertEquals(10, withToken.count { it == '"' })
        assertEquals(8, jellyfinAuthHeader(client, null).count { it == '"' })
    }

    @Test
    fun `client fields keep only letters digits dashes dots and spaces`() {
        assertEquals("0.4.1-debug", jellyfinHeaderValue("0.4.1-debug"))
        assertEquals("Bobs Phone 2", jellyfinHeaderValue("Bob's \"Phone\" #2,"))
        assertEquals("", jellyfinHeaderValue("+%=\""))
        val header = jellyfinAuthHeader(JellyfinClient("A\"nd, roid", "id=1", "v\"1", client = "Chapter\"Player"), null)
        assertEquals(8, header.count { it == '"' })
        assertTrue(header.contains("Client=\"ChapterPlayer\""), header)
        assertTrue(header.contains("Device=\"And roid\""), header)
        assertTrue(header.contains("DeviceId=\"id1\""), header)
    }

    @Test
    fun `two rows get two device ids`() {
        val one = jellyfinDeviceId("inst", "row1")
        val two = jellyfinDeviceId("inst", "row2")
        assertNotEquals(one, two)
        assertTrue(one.isNotBlank())
        assertTrue(two.isNotBlank())
        assertEquals("chapterplayer-inst-row1", one)
    }

    @Test
    fun `a device id keeps only header-safe characters`() {
        assertEquals("chapterplayer-a1b2-row-1", jellyfinDeviceId("a1b2", "row-1"))
        assertEquals("chapterplayer-ab-r1", jellyfinDeviceId("a\"b", "r,1"))
    }
}
