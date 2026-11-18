package com.chapterplayer.domain.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JellyfinDownloadsTest {
    @Test
    fun `the download address keeps the path of the server once and holds no token`() {
        assertEquals("http://host:8096/Items/abc123/Download", jellyfinDownloadUrl("http://host:8096", "abc123"))
        assertEquals("http://host:8096/jf/Items/abc123/Download", jellyfinDownloadUrl("http://host:8096/jf/", "abc123"))
        assertFalse(jellyfinDownloadUrl("http://host:8096", "abc123").contains("api_key", ignoreCase = true))
    }

    @Test
    fun `an item id with odd characters is encoded as one path part`() {
        assertEquals("http://host/Items/a%2Fb%20c/Download", jellyfinDownloadUrl("http://host", "a/b c"))
    }

    @Test
    fun `the stored extension follows the container family`() {
        assertEquals("m4a", jellyfinFileExtension("m4a"))
        assertEquals("m4a", jellyfinFileExtension("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("m4a", jellyfinFileExtension("M4B"))
        assertEquals("mp3", jellyfinFileExtension("mp3"))
        assertEquals("flac", jellyfinFileExtension("flac"))
        assertEquals("ogg", jellyfinFileExtension("opus"))
        assertEquals("aac", jellyfinFileExtension("aac"))
        assertEquals("wav", jellyfinFileExtension("wav"))
        assertEquals("audio", jellyfinFileExtension("wmv"))
        assertEquals("audio", jellyfinFileExtension(null))
    }
}
