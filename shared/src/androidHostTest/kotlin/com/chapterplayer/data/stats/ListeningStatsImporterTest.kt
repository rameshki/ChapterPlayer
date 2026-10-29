package com.chapterplayer.data.stats

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.domain.stats.ServerListeningStats
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeListeningLog
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
class ListeningStatsImporterTest {

    private class FakeServers(initial: List<ServerConfig>) : ServerRepository {
        val servers = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ServerConfig>> = servers
        override suspend fun getById(id: String): ServerConfig? = servers.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    private class StatsSource(override val type: ServerSource) : LibrarySource {
        val calls = mutableListOf<String>()
        var answer: (ServerConfig) -> ServerListeningStats? = { ServerListeningStats(mapOf(1 to 60L), mapOf("abs_x" to 60L), 60) }
        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = emptyList()
        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = emptyList()
        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()
        override suspend fun fetchListeningStats(server: ServerConfig): ServerListeningStats? {
            calls += server.id
            return answer(server)
        }
    }

    private fun server(id: String, source: ServerSource = ServerSource.ABS, token: String = "t") =
        ServerConfig(id = id, source = source, name = id, baseUrl = "http://$id.test", token = token)

    private class Rig(scope: TestScope, servers: List<ServerConfig>, online: Boolean) {
        val log = FakeListeningLog()
        val abs = StatsSource(ServerSource.ABS)
        val plex = StatsSource(ServerSource.PLEX)
        val servers = FakeServers(servers)
        val connectivity = FakeConnectivity(online)
        val importer = ListeningStatsImporter(
            this.servers, listOf(abs, plex), log, connectivity,
            CoroutineScope(scope.backgroundScope.coroutineContext + UnconfinedTestDispatcher(scope.testScheduler)),
            object : ListeningClock {
                override fun monoMs() = scope.testScheduler.currentTime
                override fun nowMs() = 5_000L
            },
        )
    }

    private fun TestScope.rig(vararg servers: ServerConfig, online: Boolean = true) = Rig(this, servers.toList(), online)

    @Test
    fun `a failed first import is tried again after a growing wait`() = runTest {
        val r = rig(server("a"))
        var fails = 2
        r.abs.answer = { if (fails-- > 0) error("offline") else ServerListeningStats(mapOf(1 to 60L)) }
        r.importer.start()
        runCurrent()
        assertEquals(1, r.abs.calls.size)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, r.abs.calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, r.abs.calls.size)
        // The second wait is twice as long.
        advanceTimeBy(59_999); runCurrent()
        assertEquals(2, r.abs.calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, r.abs.calls.size)
        assertEquals(mapOf("a" to 5_000L), r.log.imports)
        // Imported: nothing more is tried.
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(3, r.abs.calls.size)
    }

    @Test
    fun `a new sign-in tries again at once`() = runTest {
        val r = rig(server("a", token = ""))
        r.importer.start()
        runCurrent()
        // Waiting for a sign-in: not tried at all.
        assertEquals(emptyList(), r.abs.calls)
        r.abs.answer = { throw SignInRequiredException("a") }
        r.servers.servers.value = listOf(server("a", token = "t1"))
        runCurrent()
        assertEquals(listOf("a"), r.abs.calls)
        // Signed in again: the changed row is tried without waiting.
        r.abs.answer = { ServerListeningStats(mapOf(1 to 60L)) }
        r.servers.servers.value = listOf(server("a", token = "t2"))
        runCurrent()
        assertEquals(listOf("a", "a"), r.abs.calls)
        assertEquals(setOf("a"), r.log.imports.keys)
    }

    @Test
    fun `nothing is tried offline and the network coming back tries at once`() = runTest {
        val r = rig(server("a"), online = false)
        r.abs.answer = { error("no route") }
        r.importer.start()
        runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(emptyList(), r.abs.calls)
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(1, r.abs.calls.size)
        r.connectivity.online.value = false
        runCurrent()
        r.abs.answer = { ServerListeningStats(mapOf(1 to 60L)) }
        r.connectivity.online.value = true
        runCurrent()
        // Well within the 30 s wait, but the network came back.
        assertEquals(2, r.abs.calls.size)
        assertEquals(setOf("a"), r.log.imports.keys)
    }

    @Test
    fun `each Audiobookshelf server is imported once at start and Plex is skipped`() = runTest {
        val r = rig(server("a"), server("p", ServerSource.PLEX), server("b"))
        r.importer.start()
        runCurrent()
        assertEquals(listOf("a", "b"), r.abs.calls)
        assertEquals(emptyList(), r.plex.calls)
        assertEquals(mapOf("a" to 5_000L, "b" to 5_000L), r.log.imports)
        assertEquals(listOf(ImportedDay("a", 1, 60), ImportedDay("b", 1, 60)), r.log.days.value)
    }

    @Test
    fun `a server imported before is not imported again at start`() = runTest {
        val r = rig(server("a"))
        r.log.imports["a"] = 1L
        r.importer.start()
        runCurrent()
        assertEquals(emptyList(), r.abs.calls)
    }

    @Test
    fun `a server added later is imported when it appears`() = runTest {
        val r = rig(server("a"))
        r.importer.start()
        runCurrent()
        r.servers.servers.value = listOf(server("a"), server("c"))
        runCurrent()
        assertEquals(listOf("a", "c"), r.abs.calls)
    }

    @Test
    fun `an unrelated list change does not cut the wait after a failure short`() = runTest {
        val r = rig(server("a"))
        r.abs.answer = { error("offline") }
        r.importer.start()
        runCurrent()
        r.servers.servers.value = listOf(server("a"), server("p", ServerSource.PLEX))
        runCurrent()
        assertEquals(listOf("a"), r.abs.calls)
        assertEquals(emptyMap(), r.log.imports)
    }

    @Test
    fun `refresh imports every Audiobookshelf server again and says how it went`() = runTest {
        val r = rig(server("a"), server("b"), server("c", token = ""), server("d"), server("p", ServerSource.PLEX))
        r.abs.answer = { s ->
            when (s.id) {
                "b" -> throw SignInRequiredException("b")
                "d" -> error("boom")
                else -> ServerListeningStats(mapOf(2 to 30L))
            }
        }
        assertEquals(ImportOutcome(imported = 1, failed = 1, signInRequired = 2), r.importer.refresh())
        assertEquals(listOf("a", "b", "d"), r.abs.calls)
        assertEquals(listOf(ImportedDay("a", 2, 30)), r.log.days.value)
    }
}
