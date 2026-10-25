package com.chapterplayer.data.sync

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlexLengthFillerTest {

    private val plex = ServerConfig("plex1", ServerSource.PLEX, "Home Plex", "http://p.test", "t")

    private fun plexBook(key: String, section: String, durationMs: Long = 0L): AudioBook =
        testBook("plex_$key").copy(serverId = "plex1", source = ServerSource.PLEX, libraryId = section, durationMs = durationMs)

    /** Lengths per section; a section in [failing] throws, one in [gates] waits. */
    private class FakeLengths(
        val bySection: Map<String, Map<String, Long>>,
        val failing: Set<String> = emptySet(),
        val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ) : PlexSectionLengths {
        val asked = mutableListOf<String>()
        override suspend fun albumLengths(server: ServerConfig, sectionId: String, onProgress: (Int) -> Unit): Map<String, Long> {
            asked += sectionId
            onProgress(50)
            gates[sectionId]?.await()
            if (sectionId in failing) error("page 3 failed")
            return bySection[sectionId].orEmpty()
        }
    }

    private fun length(books: FakeBookRepository, id: String) = books.books.value.first { it.id == id }.durationMs

    @Test
    fun `fills the unknown lengths of each section from the sums`() = runTest {
        val books = FakeBookRepository(listOf(plexBook("1", "s1"), plexBook("2", "s1"), plexBook("3", "s2")))
        val lengths = FakeLengths(mapOf("s1" to mapOf("1" to 100L, "2" to 200L), "s2" to mapOf("3" to 300L)))
        PlexLengthFiller(books, lengths, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).fill(plex)
        assertEquals(listOf(100L, 200L, 300L), listOf(length(books, "plex_1"), length(books, "plex_2"), length(books, "plex_3")))
    }

    @Test
    fun `a known length is never overwritten and a section with nothing missing is not read`() = runTest {
        val books = FakeBookRepository(listOf(plexBook("1", "s1", durationMs = 999L), plexBook("2", "s2")))
        val lengths = FakeLengths(mapOf("s1" to mapOf("1" to 5L), "s2" to mapOf("2" to 7L)))
        PlexLengthFiller(books, lengths, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).fill(plex)
        assertEquals(999L, length(books, "plex_1"))
        assertEquals(7L, length(books, "plex_2"))
        assertEquals(listOf("s2"), lengths.asked)
    }

    @Test
    fun `a failed section writes nothing and the others still fill`() = runTest {
        val books = FakeBookRepository(listOf(plexBook("1", "s1"), plexBook("2", "s2")))
        val lengths = FakeLengths(mapOf("s1" to mapOf("1" to 5L), "s2" to mapOf("2" to 7L)), failing = setOf("s1"))
        PlexLengthFiller(books, lengths, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).fill(plex)
        assertEquals(0L, length(books, "plex_1"))
        assertEquals(7L, length(books, "plex_2"))
    }

    @Test
    fun `an album the sums do not know keeps its unknown length`() = runTest {
        val books = FakeBookRepository(listOf(plexBook("1", "s1"), plexBook("2", "s1")))
        val lengths = FakeLengths(mapOf("s1" to mapOf("1" to 5L, "2" to 0L)))
        PlexLengthFiller(books, lengths, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).fill(plex)
        assertEquals(5L, length(books, "plex_1"))
        assertEquals(0L, length(books, "plex_2"))
    }

    @Test
    fun `progress shows while reading and one pass per server runs at a time`() = runTest {
        val books = FakeBookRepository(listOf(plexBook("1", "s1")))
        val gate = CompletableDeferred<Unit>()
        val lengths = FakeLengths(mapOf("s1" to mapOf("1" to 5L)), gates = mapOf("s1" to gate))
        val filler = PlexLengthFiller(books, lengths, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        filler.request(plex)
        filler.request(plex)
        assertEquals(mapOf("plex1" to 50), filler.progress.value)
        assertEquals(listOf("s1"), lengths.asked)
        gate.complete(Unit)
        assertEquals(emptyMap(), filler.progress.value)
        assertEquals(5L, length(books, "plex_1"))
    }

    @Test
    fun `audiobookshelf servers are left alone`() = runTest {
        val abs = ServerConfig("abs1", ServerSource.ABS, "A", "http://a.test", "t")
        val books = FakeBookRepository(listOf(testBook("a").copy(serverId = "abs1", durationMs = 0L)))
        val lengths = FakeLengths(emptyMap())
        PlexLengthFiller(books, lengths, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).fill(abs)
        assertTrue(lengths.asked.isEmpty())
    }

    // ---- with the sync ----

    @Test
    fun `a Plex sync asks for the lengths afterwards and does not wait for them`() = runTest {
        val books = FakeBookRepository(listOf(plexBook("1", "s1")))
        val gate = CompletableDeferred<Unit>()
        val lengths = FakeLengths(mapOf("s1" to mapOf("1" to 5L)), gates = mapOf("s1" to gate))
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val filler = PlexLengthFiller(books, lengths, scope)
        val servers = FakeServerList(listOf(plex))
        val sync = LibrarySyncCoordinator(books, servers, scope, lengthFiller = filler)

        sync.syncNow("plex1")

        // The sync is done while the lengths are still being read.
        assertEquals(listOf("s1"), lengths.asked)
        assertEquals(false, sync.state.value.isSyncing)
        assertEquals(0L, length(books, "plex_1"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sync.serverStatus.collect {} }
        assertEquals("Reading lengths… 50%", sync.serverStatus.value["plex1"]?.lengthsNote)
        gate.complete(Unit)
        assertEquals(5L, length(books, "plex_1"))
    }
}
