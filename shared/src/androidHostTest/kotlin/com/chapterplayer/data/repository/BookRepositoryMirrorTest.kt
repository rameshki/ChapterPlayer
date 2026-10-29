package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.merge.MergedProgressPeers
import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keeping the local rows of a merged book's copies in step (an Audiobookshelf-like and a Plex-like copy). */
class BookRepositoryMirrorTest {

    private class FakeBookDao(initial: List<BookEntity>) : BookDao {
        val books = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<BookEntity>> = books
        override fun observeBySource(source: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.source == source } }
        override fun observeByServer(serverId: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.serverId == serverId } }
        override suspend fun getById(id: String): BookEntity? = books.value.firstOrNull { it.id == id }
        override suspend fun getByServer(serverId: String): List<BookEntity> = books.value.filter { it.serverId == serverId }
        override suspend fun insertAll(books: List<BookEntity>) {}
        override suspend fun insert(book: BookEntity) {}
        override suspend fun deleteByServer(serverId: String) {}
        override suspend fun deleteByLibrary(serverId: String, libraryId: String) {}
        override suspend fun deleteAll() {}
        override suspend fun getLastCacheTime(serverId: String): Long? = null
        override suspend fun updateChapters(id: String, json: String) {}
        override suspend fun updateDuration(id: String, durationMs: Long) {}
    }

    /** One server type; remembers pushes, answers progress from [progress]. */
    private class Source(override val type: ServerSource) : LibrarySource {
        var failure: Throwable? = null
        val progress = HashMap<String, ServerProgress>()
        var all: Map<String, ServerProgress> = emptyMap()
        val pushed = mutableListOf<List<Any>>()
        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = emptyList()
        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = emptyList()
        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()
        override suspend fun fetchProgress(server: ServerConfig, bookId: String): ServerProgress? {
            failure?.let { throw it }
            return progress[bookId]
        }
        override suspend fun pushProgress(server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean) {
            failure?.let { throw it }
            pushed += listOf(bookId, positionMs, isFinished)
            // Like the servers: finished keeps the time, not finished starts over.
            progress[bookId] = ServerProgress(if (isFinished) positionMs else 0L, durationMs, isFinished, 50_000L)
        }
        override suspend fun fetchAllProgress(server: ServerConfig): Map<String, ServerProgress> = all
    }

    private class FakePeers : ProgressMirrorPeers {
        var groups: Map<String, List<MirrorPeer>> = mapOf(
            "abs_1" to listOf(MirrorPeer("plex_1", "srv_plex", PLEX_LENGTH)),
            "plex_1" to listOf(MirrorPeer("abs_1", "srv_abs", ABS_LENGTH)),
        )
        override suspend fun peersOf(bookId: String): List<MirrorPeer> = groups[bookId].orEmpty()
    }

    private class Fixture(
        val repo: BookRepositoryImpl,
        val abs: Source,
        val plex: Source,
        val progress: InMemoryProgressDao,
        val pending: InMemoryPendingProgressDao,
        val peers: FakePeers,
    ) {
        suspend fun row(id: String): ReadingProgress? = progress.get(id)?.toDomain()
    }

    private val bookRows = listOf(
        BookEntity("abs_1", "The Book", "A", "N", ABS_LENGTH, "[]", "u", null, "ABS", "srv_abs", 0L),
        BookEntity("plex_1", "The Book", "A", "", PLEX_LENGTH, "[]", "u", null, "PLEX", "srv_plex", 0L),
    )

    private fun fixture(local: List<ProgressEntity> = emptyList(), now: Long = 40_000L, peers: ProgressMirrorPeers? = null): Fixture {
        val abs = Source(ServerSource.ABS)
        val plex = Source(ServerSource.PLEX)
        val progressDao = InMemoryProgressDao(local)
        val pendingDao = InMemoryPendingProgressDao()
        val fakePeers = FakePeers()
        val books = bookRows
        val servers = listOf(
            ServerConfig("srv_abs", ServerSource.ABS, "A", "http://abs.test", "tok-secret"),
            ServerConfig("srv_plex", ServerSource.PLEX, "P", "http://plex.test", "tok-secret"),
        )
        val repo = BookRepositoryImpl(
            FakeBookDao(books), progressDao, InMemoryLibraryDao(), InMemoryCollectionDao(), FakeServerList(servers), listOf(abs, plex),
            pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(), clock = { now },
            mirrorPeers = peers ?: fakePeers,
        )
        return Fixture(repo, abs, plex, progressDao, pendingDao, fakePeers)
    }

    @Test
    fun `listening on one copy moves the other copy's row within the same save`() = runTest {
        val f = fixture()
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), loadedSinceMs = 0L)

        assertEquals(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), f.row("abs_1"))
        assertEquals(ReadingProgress("plex_1", 6 * HOUR, PLEX_LENGTH, 1_000L, isFinished = false, mirrored = true), f.row("plex_1"))
        // Nothing is sent to the other server from a local save; the pusher does that.
        assertTrue(f.plex.pushed.isEmpty())
        assertTrue(f.pending.rows.value.isEmpty())
    }

    @Test
    fun `a copy whose own listening is newer is not overwritten`() = runTest {
        val own = ProgressEntity("plex_1", 1 * HOUR, PLEX_LENGTH, 9_000L, false)
        val f = fixture(local = listOf(own))
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), loadedSinceMs = 0L)
        assertEquals(own, f.progress.get("plex_1"))
    }

    @Test
    fun `listening on the mirrored copy makes its row its own again and moves the first copy`() = runTest {
        val f = fixture()
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), loadedSinceMs = 0L)
        f.repo.savePlaybackProgress(ReadingProgress("plex_1", 9 * HOUR, PLEX_LENGTH, 2_000L), loadedSinceMs = 0L)
        assertEquals(false, f.row("plex_1")?.mirrored)
        assertEquals(ReadingProgress("abs_1", 7 * HOUR + 30 * 60_000L, ABS_LENGTH, 2_000L, isFinished = false, mirrored = true), f.row("abs_1"))
    }

    @Test
    fun `a split pair or the setting off mirrors nothing`() = runTest {
        val f = fixture()
        f.peers.groups = emptyMap()
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), loadedSinceMs = 0L)
        assertNull(f.row("plex_1"))
    }

    @Test
    fun `newer server progress of one copy is stored and the other copy follows`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 1 * HOUR, ABS_LENGTH, 100L, false)))
        f.repo.saveServerProgress("abs_1", ServerProgress(5 * HOUR, ABS_LENGTH, false, 3_000L))
        assertEquals(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 3_000L), f.row("abs_1"))
        assertEquals(ReadingProgress("plex_1", 6 * HOUR, PLEX_LENGTH, 3_000L, isFinished = false, mirrored = true), f.row("plex_1"))

        // The library sync's merge of every book goes the same way.
        f.abs.all = mapOf("abs_1" to ServerProgress(6 * HOUR, ABS_LENGTH, false, 4_000L))
        f.repo.mergeAllServerProgress("srv_abs")
        assertEquals(PLEX_LENGTH * 6 / 10, f.row("plex_1")?.positionMs)
        assertEquals(4_000L, f.row("plex_1")?.lastPlayedAt)
    }

    @Test
    fun `mark as finished applies to every copy, each through its own server`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 100L, false)))
        val outcome = f.repo.markFinished("abs_1", finished = true)

        assertEquals(MarkOutcome.SYNCED, outcome.getOrNull())
        assertEquals(true, f.row("abs_1")?.isFinished)
        assertEquals(true, f.row("plex_1")?.isFinished)
        assertEquals(listOf<List<Any>>(listOf("abs_1", 5 * HOUR, true)), f.abs.pushed)
        assertEquals("plex_1", f.plex.pushed.single()[0])
        assertEquals(true, f.plex.pushed.single()[2])
    }

    @Test
    fun `mark as not finished resets every copy to the start, and an unreachable copy waits in the queue`() = runTest {
        val f = fixture(
            local = listOf(
                ProgressEntity("abs_1", ABS_LENGTH, ABS_LENGTH, 100L, true),
                ProgressEntity("plex_1", PLEX_LENGTH, PLEX_LENGTH, 100L, true, mirrored = true),
            )
        )
        f.plex.failure = IOException("offline")
        val outcome = f.repo.markFinished("abs_1", finished = false)

        assertEquals(MarkOutcome.QUEUED, outcome.getOrNull())
        assertEquals(0L, f.row("abs_1")?.positionMs)
        assertEquals(false, f.row("abs_1")?.isFinished)
        assertEquals(0L, f.row("plex_1")?.positionMs)
        assertEquals(false, f.row("plex_1")?.isFinished)
        assertEquals(false, f.pending.get("plex_1")?.markedFinished)
        assertNull(f.pending.get("abs_1"))
    }

    @Test
    fun `with mirroring off a mark changes the one copy only`() = runTest {
        val f = fixture()
        f.peers.groups = emptyMap()
        f.repo.markFinished("abs_1", finished = true)
        assertNull(f.row("plex_1"))
        assertTrue(f.plex.pushed.isEmpty())
    }

    @Test
    fun `with the app's grouping a split pair and the switch off stop the mirror at once`() = runTest {
        // The lengths differ too much for an automatic match: the user merged them ("Merge with…").
        val merged = MergeOverrides(merges = setOf("abs_1" to "plex_1"))
        val overrides = MutableStateFlow(merged)
        val enabled = MutableStateFlow(true)
        val peers = MergedProgressPeers(MutableStateFlow(bookRows.map { it.toDomain(emptyList()) }), overrides, enabled, backgroundScope)
        val f = fixture(peers = peers)

        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), loadedSinceMs = 0L)
        assertEquals(6 * HOUR, f.row("plex_1")?.positionMs)

        overrides.value = MergeOverrides(splits = setOf("abs_1" to "plex_1"))
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 8 * HOUR, ABS_LENGTH, 2_000L), loadedSinceMs = 0L)
        // The split leaves each copy the progress it has.
        assertEquals(6 * HOUR, f.row("plex_1")?.positionMs)

        overrides.value = merged
        enabled.value = false
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 9 * HOUR, ABS_LENGTH, 3_000L), loadedSinceMs = 0L)
        assertEquals(6 * HOUR, f.row("plex_1")?.positionMs)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val ABS_LENGTH = 10 * HOUR
        const val PLEX_LENGTH = 12 * HOUR
    }
}
