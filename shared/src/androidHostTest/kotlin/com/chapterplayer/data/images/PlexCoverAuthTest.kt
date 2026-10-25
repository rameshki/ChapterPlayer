package com.chapterplayer.data.images

import coil3.network.NetworkHeaders
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlexCoverAuthTest {

    private val plex = ServerConfig("p", ServerSource.PLEX, "Plex", "http://plex.test:32400", "plex-tok")
    private val abs = ServerConfig("a", ServerSource.ABS, "ABS", "http://abs.test", "abs-tok")

    @Test
    fun `a Plex cover gets the token header and keeps the headers it had`() {
        val before = NetworkHeaders.Builder().set("Accept", "image/*").build()

        val after = plexCoverHeaders("http://plex.test:32400/library/metadata/1/thumb/2", before, listOf(plex))

        assertEquals("plex-tok", after?.get("X-Plex-Token"))
        assertEquals("image/*", after?.get("Accept"))
    }

    @Test
    fun `other images are left alone`() {
        assertNull(plexCoverHeaders("http://abs.test/api/items/1/cover", NetworkHeaders.EMPTY, listOf(abs, plex)))
        assertNull(plexCoverHeaders(42, NetworkHeaders.EMPTY, listOf(plex)))
        assertNull(plexCoverHeaders(null, NetworkHeaders.EMPTY, listOf(plex)))
    }

    @Test
    fun `the image servers are the signed-in Plex servers and follow changes`() = runTest(UnconfinedTestDispatcher()) {
        val list = FakeServerList(listOf(abs, plex, plex.copy(id = "q", token = "")))
        val servers = PlexImageServers(list, backgroundScope)

        assertEquals(listOf(plex), servers.plexServers())
        list.flow.value = emptyList()
        assertEquals(emptyList(), servers.plexServers())
    }

    @Test
    fun `a failing server list means no tokens, not a crash`() = runTest(UnconfinedTestDispatcher()) {
        val failing = object : ServerRepository by FakeServerList() {
            override fun observeAll(): Flow<List<ServerConfig>> = flow { throw IllegalStateException("db") }
        }

        assertEquals(emptyList(), PlexImageServers(failing, backgroundScope).plexServers())
    }
}
