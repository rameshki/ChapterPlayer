package com.chapterplayer.domain.plex

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexImageAuthTest {

    private val plex = ServerConfig("p", ServerSource.PLEX, "Plex", "https://plex.test:32400", "plex-tok")
    private val abs = ServerConfig("a", ServerSource.ABS, "ABS", "https://abs.test", "abs-tok")

    @Test
    fun `a cover on a Plex server gets that server's token`() {
        assertEquals("plex-tok", plexTokenFor("https://plex.test:32400/library/metadata/1/thumb/2", listOf(abs, plex)))
    }

    @Test
    fun `a base address with a trailing slash matches too`() {
        assertEquals("plex-tok", plexTokenFor("https://plex.test:32400/x", listOf(plex.copy(baseUrl = "https://plex.test:32400/"))))
    }

    @Test
    fun `each Plex server gets its own token`() {
        val other = plex.copy(id = "q", baseUrl = "https://other.test:32400", token = "other-tok")

        assertEquals("other-tok", plexTokenFor("https://other.test:32400/library/metadata/1/thumb/2", listOf(plex, other)))
    }

    @Test
    fun `no token goes to another host, port, scheme or a look-alike address`() {
        val servers = listOf(plex)
        assertNull(plexTokenFor("https://evil.test/library/metadata/1/thumb/2", servers))
        assertNull(plexTokenFor("https://plex.test:3240/library", servers))
        assertNull(plexTokenFor("https://plex.test:324000/library", servers))
        assertNull(plexTokenFor("http://plex.test:32400/library", servers))
        assertNull(plexTokenFor("https://plex.test:32400.evil.test/library", servers))
        assertNull(plexTokenFor("https://plex.test:32400", servers)) // the bare base is no cover
    }

    @Test
    fun `a cover under another connection of the same server gets its token, nothing else does`() {
        val switched = plex.copy(
            baseUrl = "http://192.0.2.11:32400",
            alternateBaseUrls = listOf("https://plex.test:32400", "https://relay.test:8443")
        )

        assertEquals("plex-tok", plexTokenFor("https://plex.test:32400/library/metadata/1/thumb/2", listOf(switched)))
        assertEquals("plex-tok", plexTokenFor("http://192.0.2.11:32400/library/metadata/1/thumb/2", listOf(switched)))
        assertNull(plexTokenFor("https://plex.test:3240/library", listOf(switched)))
        assertNull(plexTokenFor("https://evil.test/library", listOf(switched)))
        // An Audiobookshelf server never sends its token to a listed address.
        assertNull(plexTokenFor("https://plex.test:32400/x", listOf(abs.copy(alternateBaseUrls = listOf("https://plex.test:32400")))))
    }

    @Test
    fun `an Audiobookshelf cover gets no Plex token`() {
        assertNull(plexTokenFor("https://abs.test/api/items/1/cover", listOf(abs, plex)))
    }

    @Test
    fun `a server without a token or address sends nothing`() {
        assertNull(plexTokenFor("https://plex.test:32400/x", listOf(plex.copy(token = ""))))
        assertNull(plexTokenFor("/x", listOf(plex.copy(baseUrl = ""))))
    }
}
