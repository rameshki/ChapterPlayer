package com.chapterplayer.data.progress

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ServerProgressRefresherTest {

    private class FakeServers(initial: List<ServerConfig>) : ServerRepository {
        val servers = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ServerConfig>> = servers
        override suspend fun getById(id: String): ServerConfig? = servers.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    private class Rig(scope: CoroutineScope, now: () -> Long, online: Boolean, servers: List<ServerConfig>) {
        val repo = FakeBookRepository()
        val connectivity = FakeConnectivity(online)
        val servers = FakeServers(servers)
        val refresher = ServerProgressRefresher(repo, this.servers, connectivity, scope, now)
    }

    private fun server(id: String, source: ServerSource = ServerSource.ABS) =
        ServerConfig(id = id, source = source, name = id, baseUrl = "http://$id.test", token = "t")

    private fun TestScope.rig(online: Boolean = true, vararg servers: ServerConfig) = Rig(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime },
        online,
        servers.toList()
    )

    @Test
    fun `each Audiobookshelf server is merged once at start while online and other servers are skipped`() = runTest {
        val r = rig(true, server("a"), server("p", ServerSource.PLEX), server("b"))
        r.refresher.start()
        runCurrent()
        assertEquals(listOf("a", "b"), r.repo.mergedServers)
    }

    @Test
    fun `nothing is merged while offline and the merge happens when the network returns`() = runTest {
        val r = rig(false, server("a"))
        r.refresher.start()
        runCurrent()
        assertEquals(emptyList(), r.repo.mergedServers)
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("a"), r.repo.mergedServers)
    }

    @Test
    fun `going online again within five minutes does not merge again but later it does`() = runTest {
        val r = rig(true, server("a"))
        r.refresher.start()
        runCurrent()
        r.connectivity.online.value = false
        runCurrent()
        advanceTimeBy(299_999)
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("a"), r.repo.mergedServers)
        r.connectivity.online.value = false
        runCurrent()
        advanceTimeBy(2)
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("a", "a"), r.repo.mergedServers)
    }

    @Test
    fun `a merge that fails does not stop the other servers`() = runTest {
        val r = rig(true, server("a"), server("b"))
        r.repo.onMergeAll = { id -> if (id == "a") throw RuntimeException("down") }
        r.refresher.start()
        runCurrent()
        assertEquals(listOf("a", "b"), r.repo.mergedServers)
    }

    @Test
    fun `starting twice merges once`() = runTest {
        val r = rig(true, server("a"))
        r.refresher.start()
        r.refresher.start()
        runCurrent()
        assertEquals(listOf("a"), r.repo.mergedServers)
    }

    @Test
    fun `a server added later is merged the next time the network returns without waiting for the others`() = runTest {
        val r = rig(true, server("a"))
        r.refresher.start()
        runCurrent()
        r.servers.servers.value = listOf(server("a"), server("c"))
        r.connectivity.online.value = false
        runCurrent()
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("a", "c"), r.repo.mergedServers)
    }
}
