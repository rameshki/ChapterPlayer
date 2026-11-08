package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerNamesTest {

    private fun server(id: String, name: String, url: String, source: ServerSource = ServerSource.ABS) = ServerConfig(id, source, name, url, "secret-token")

    @Test
    fun `distinct names stay as they are`() {
        val names = serverDisplayNames(listOf(server("1", "Home", "http://a.example"), server("2", "Work", "http://b.example")))
        assertEquals(mapOf("1" to "Home", "2" to "Work"), names)
    }

    @Test
    fun `the same name gets the address host`() {
        val names = serverDisplayNames(listOf(
            server("1", "Home", "https://books.example:13378/abs"),
            server("2", "home ", "http://user@other.example/"),
        ))
        assertEquals(mapOf("1" to "Home (books.example:13378)", "2" to "home (other.example)"), names)
    }

    @Test
    fun `the same name and host get a number`() {
        val names = serverDisplayNames(listOf(
            server("1", "Home", "http://a.example"),
            server("2", "Home", "http://a.example", ServerSource.PLEX),
        ))
        assertEquals(mapOf("1" to "Home #1", "2" to "Home #2"), names)
    }

    @Test
    fun `a blank name is the server type and is told apart the same way`() {
        val names = serverDisplayNames(listOf(
            server("1", " ", "http://a.example"),
            server("2", "", "http://b.example"),
            server("3", "", "http://c.example", ServerSource.PLEX),
        ))
        assertEquals(mapOf("1" to "Audiobookshelf (a.example)", "2" to "Audiobookshelf (b.example)", "3" to "Plex"), names)
    }

    @Test
    fun `never the id or the token`() {
        val names = serverDisplayNames(listOf(server("id-1", "", "not a url"), server("id-2", "", "not a url")))
        assertEquals(mapOf("id-1" to "Audiobookshelf #1", "id-2" to "Audiobookshelf #2"), names)
        names.values.forEach { assertTrue(!it.contains("secret") && !it.contains("id-")) }
    }

    @Test
    fun `a blank-named Jellyfin server is Jellyfin`() {
        val names = serverDisplayNames(listOf(
            server("1", "", "http://a.example"),
            server("2", " ", "http://jf.example/jellyfin", ServerSource.JELLYFIN),
        ))
        assertEquals(mapOf("1" to "Audiobookshelf", "2" to "Jellyfin"), names)
    }

    @Test
    fun `two Jellyfin servers named alike get the host as for Audiobookshelf`() {
        val names = serverDisplayNames(listOf(
            server("1", "Home", "https://jf.example/jellyfin", ServerSource.JELLYFIN),
            server("2", "home", "http://other.example:8096", ServerSource.JELLYFIN),
        ))
        assertEquals(mapOf("1" to "Home (jf.example)", "2" to "home (other.example:8096)"), names)
        val blank = serverDisplayNames(listOf(
            server("1", "", "https://jf.example", ServerSource.JELLYFIN),
            server("2", "", "https://jf2.example", ServerSource.JELLYFIN),
        ))
        assertEquals(mapOf("1" to "Jellyfin (jf.example)", "2" to "Jellyfin (jf2.example)"), blank)
    }
}
