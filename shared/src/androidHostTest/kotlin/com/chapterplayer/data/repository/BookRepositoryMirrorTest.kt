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
import com.chapterplayer.domain.playback.mirrorPosition
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.data.local.entity.PendingProgressEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Keeping the local rows of a merged book's copies in step (an Audiobookshelf-like and a Plex-like copy). */
@OptIn(ExperimentalCoroutinesApi::class)
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
        /** Set to make every push hang (a server that does not answer). */
        var hang = false
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
        override suspend fun pushProgress(
            server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean, playedAtMs: Long?, setNotFinished: Boolean,
        ) {
            if (hang) awaitCancellation()
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
        val jf: Source,
        val progress: InMemoryProgressDao,
        val pending: InMemoryPendingProgressDao,
        val peers: FakePeers,
        /** The repository's clock; tests may move it on. */
        val clock: LongArray,
    ) {
        suspend fun row(id: String): ReadingProgress? = progress.get(id)?.toDomain()
    }

    private val bookRows = listOf(
        BookEntity("abs_1", "The Book", "A", "N", ABS_LENGTH, "[]", "u", null, "ABS", "srv_abs", 0L),
        BookEntity("plex_1", "The Book", "A", "", PLEX_LENGTH, "[]", "u", null, "PLEX", "srv_plex", 0L),
        BookEntity("jf_1", "The Book", "A", "", JF_LENGTH, "[]", "", null, "JELLYFIN", "srv_jf", 0L),
    )

    private fun fixture(
        local: List<ProgressEntity> = emptyList(),
        now: Long = 40_000L,
        peers: ProgressMirrorPeers? = null,
        loaded: String? = null,
        queued: List<PendingProgressEntity> = emptyList(),
    ): Fixture {
        val abs = Source(ServerSource.ABS)
        val plex = Source(ServerSource.PLEX)
        val jf = Source(ServerSource.JELLYFIN)
        val progressDao = InMemoryProgressDao(local)
        val pendingDao = InMemoryPendingProgressDao(queued)
        val fakePeers = FakePeers()
        val clock = longArrayOf(now)
        val books = bookRows
        val servers = listOf(
            ServerConfig("srv_abs", ServerSource.ABS, "A", "http://abs.test", "tok-secret"),
            ServerConfig("srv_plex", ServerSource.PLEX, "P", "http://plex.test", "tok-secret"),
            ServerConfig("srv_jf", ServerSource.JELLYFIN, "J", "http://jf.test", "tok-secret"),
        )
        val repo = BookRepositoryImpl(
            FakeBookDao(books), progressDao, InMemoryLibraryDao(), InMemoryCollectionDao(), FakeServerList(servers), listOf(abs, plex, jf),
            pendingDao = pendingDao, bookmarkDao = InMemoryBookmarkDao(), historyDao = InMemoryHistoryDao(), clock = { clock[0] },
            mirrorPeers = peers ?: fakePeers,
            loadedBookId = { loaded },
            markServerWaitMs = 300L,
        )
        return Fixture(repo, abs, plex, jf, progressDao, pendingDao, fakePeers, clock)
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
        // Every copy starts over at 0 and its server is sent finished at 0.
        assertEquals(0L, f.row("abs_1")?.positionMs)
        assertEquals(0L, f.row("plex_1")?.positionMs)
        assertEquals(listOf<List<Any>>(listOf("abs_1", 0L, true)), f.abs.pushed)
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

    // ---- review fixes: the loaded copy and the user's mark, echoes, clocks, queued mirrors ----

    @Test
    fun `a newer merge of the other copy never rewrites the loaded copy, so its finished mark holds`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 100L, false)), loaded = "abs_1")
        f.repo.markFinished("abs_1", finished = true)
        // Another device listened to the Plex copy after the mark.
        f.clock[0] = 90_000L
        f.repo.saveServerProgress("plex_1", ServerProgress(1 * HOUR, PLEX_LENGTH, false, 60_000L))
        assertEquals(1 * HOUR, f.row("plex_1")?.positionMs)
        assertEquals(true, f.row("abs_1")?.isFinished)
        // The player's next save of the loaded book (at the start, where the mark rewound it) keeps the user's mark.
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 1_000L, ABS_LENGTH, 70_000L), loadedSinceMs = 0L)
        assertEquals(true, f.row("abs_1")?.isFinished)
    }

    @Test
    fun `a newer merge of the other copy leaves the loaded copy alone, finished or not`() = runTest {
        val own = ProgressEntity("abs_1", 2 * HOUR, ABS_LENGTH, 1_000L, false)
        val f = fixture(local = listOf(own), loaded = "abs_1")
        f.repo.saveServerProgress("plex_1", ServerProgress(9 * HOUR, PLEX_LENGTH, false, 20_000L))
        assertEquals(9 * HOUR, f.row("plex_1")?.positionMs)
        assertEquals(own, f.progress.get("abs_1"))
    }

    @Test
    fun `when the book's own mark fails, the other copies are not touched`() = runTest {
        val f = fixture()
        f.peers.groups = mapOf("missing" to listOf(MirrorPeer("plex_1", "srv_plex", PLEX_LENGTH)))
        assertTrue(f.repo.markFinished("missing", finished = true).isFailure)
        assertNull(f.row("plex_1"))
        assertTrue(f.plex.pushed.isEmpty())
    }

    @Test
    fun `a copy carrying the user's mark is not rewritten by a mirror even when the player is not known`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 100L, false)))
        f.repo.markFinished("abs_1", finished = true)
        f.clock[0] = 90_000L
        f.repo.saveServerProgress("plex_1", ServerProgress(1 * HOUR, PLEX_LENGTH, false, 60_000L))
        assertEquals(1 * HOUR, f.row("plex_1")?.positionMs)
        assertEquals(true, f.row("abs_1")?.isFinished)
        assertEquals(false, f.row("abs_1")?.mirrored)
    }

    @Test
    fun `the server echoing the position the app pushed keeps the mirrored row and its time`() = runTest {
        val mirrored = ProgressEntity("plex_1", 6 * HOUR, PLEX_LENGTH, 1_000L, false, mirrored = true)
        val f = fixture(local = listOf(mirrored))
        f.repo.saveServerProgress("plex_1", ServerProgress(6 * HOUR + 4_000L, PLEX_LENGTH, false, 2_000L))
        assertEquals(mirrored, f.progress.get("plex_1"))
    }

    @Test
    fun `a server time in the future is stored as now`() = runTest {
        val f = fixture(now = 40_000L)
        f.repo.saveServerProgress("plex_1", ServerProgress(1 * HOUR, PLEX_LENGTH, false, 9_000_000_000L))
        assertEquals(40_000L, f.row("plex_1")?.lastPlayedAt)
    }

    @Test
    fun `a queued mirror older than the server's progress gives way to the merge, a real queued row does not`() = runTest {
        val mirrorRow = PendingProgressEntity("plex_1", "srv_plex", 6 * HOUR, PLEX_LENGTH, 0L, 1_000L, 0, mirrorOf = "abs_1")
        val f = fixture(queued = listOf(mirrorRow))
        f.repo.saveServerProgress("plex_1", ServerProgress(2 * HOUR, PLEX_LENGTH, false, 2_000L))
        assertEquals(2 * HOUR, f.row("plex_1")?.positionMs)
        assertNull(f.pending.get("plex_1"))

        val real = PendingProgressEntity("abs_1", "srv_abs", 1 * HOUR, ABS_LENGTH, 0L, 1_000L, 0)
        val g = fixture(queued = listOf(real))
        g.repo.saveServerProgress("abs_1", ServerProgress(2 * HOUR, ABS_LENGTH, false, 2_000L))
        assertNull(g.row("abs_1"))
        assertEquals(real, g.pending.get("abs_1"))
    }

    @Test
    fun `a mark that could not change one copy says so`() = runTest {
        val f = fixture()
        f.peers.groups = mapOf("abs_1" to listOf(MirrorPeer("plex_1", "srv_plex", PLEX_LENGTH), MirrorPeer("gone", "srv_plex", 1L)))
        assertEquals(MarkOutcome.PARTIAL, f.repo.markFinished("abs_1", finished = true).getOrNull())
        assertEquals(true, f.row("plex_1")?.isFinished)
    }

    @Test
    fun `a server that does not answer does not hold the mark up, the copy waits in the queue`() = runTest {
        val f = fixture()
        f.plex.hang = true
        val started = System.currentTimeMillis()
        val outcome = f.repo.markFinished("abs_1", finished = true)
        assertEquals(MarkOutcome.QUEUED, outcome.getOrNull())
        assertEquals(true, f.pending.get("plex_1")?.markedFinished)
        assertTrue(System.currentTimeMillis() - started < 5_000L, "the mark waited for the server")
    }

    // ---- three copies (J2: Audiobookshelf, Plex and Jellyfin) ----

    private fun Fixture.threeCopies() {
        val all = listOf(MirrorPeer("abs_1", "srv_abs", ABS_LENGTH), MirrorPeer("plex_1", "srv_plex", PLEX_LENGTH), MirrorPeer("jf_1", "srv_jf", JF_LENGTH))
        peers.groups = all.associate { me -> me.bookId to all.filter { it.bookId != me.bookId } }
    }

    @Test
    fun `listening on each of three copies writes the other two rows in the same save`() = runTest {
        val f = fixture()
        f.threeCopies()
        val lengths = mapOf("abs_1" to ABS_LENGTH, "plex_1" to PLEX_LENGTH, "jf_1" to JF_LENGTH)
        var at = 1_000L
        var quarter = 0L
        for ((id, length) in lengths) {
            at += 1_000L
            quarter++ // a new place each time: a row within the echo window of its own place is kept
            f.repo.savePlaybackProgress(ReadingProgress(id, length / 4 * quarter, length, at), loadedSinceMs = 0L)
            assertEquals(ReadingProgress(id, length / 4 * quarter, length, at), f.row(id))
            for ((other, otherLength) in lengths - id) {
                val mapped = mirrorPosition(length / 4 * quarter, length, otherLength)!!
                assertEquals(ReadingProgress(other, mapped, otherLength, at, isFinished = false, mirrored = true), f.row(other), "$id -> $other")
            }
        }
        // A save sends nothing; the pusher queues the mirrors.
        assertTrue(f.abs.pushed.isEmpty() && f.plex.pushed.isEmpty() && f.jf.pushed.isEmpty())
    }

    @Test
    fun `the loaded copy is never rewritten by a save of another copy`() = runTest {
        val f = fixture(loaded = "jf_1", local = listOf(ProgressEntity("jf_1", 2 * HOUR, JF_LENGTH, 500L, false)))
        f.threeCopies()
        f.repo.savePlaybackProgress(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 1_000L), loadedSinceMs = 0L)
        assertEquals(ReadingProgress("jf_1", 2 * HOUR, JF_LENGTH, 500L), f.row("jf_1"))
        assertEquals(true, f.row("plex_1")?.mirrored)
    }

    @Test
    fun `a mark on three copies with the Plex server down is queued for Plex only`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("jf_1", 5 * HOUR, JF_LENGTH, 100L, false)))
        f.threeCopies()
        f.plex.failure = IOException("offline")

        assertEquals(MarkOutcome.QUEUED, f.repo.markFinished("jf_1", finished = true).getOrNull())

        assertEquals(listOf("jf_1", "abs_1", "plex_1").map { true }, listOf("jf_1", "abs_1", "plex_1").map { f.row(it)?.isFinished })
        assertEquals("jf_1", f.jf.pushed.single()[0])
        assertEquals("abs_1", f.abs.pushed.single()[0])
        assertEquals(true, f.pending.get("plex_1")?.markedFinished)
        assertNull(f.pending.get("jf_1"))
        assertNull(f.pending.get("abs_1"))
    }

    @Test
    fun `a mark on three copies that cannot change one copy and waits for another is partial and queued`() = runTest {
        val f = fixture()
        f.peers.groups = mapOf(
            "jf_1" to listOf(MirrorPeer("abs_1", "srv_abs", ABS_LENGTH), MirrorPeer("plex_1", "srv_plex", PLEX_LENGTH), MirrorPeer("gone", "srv_abs", 1L)),
        )
        f.plex.failure = IOException("offline")
        assertEquals(MarkOutcome.PARTIAL_QUEUED, f.repo.markFinished("jf_1", finished = true).getOrNull())
    }

    // ---- Cross sync when a server is added (syncCopies) ----

    @Test
    fun `cross sync brings the older copy to the newer one, locally and in the send queue`() = runTest {
        // Plex holds the newer place (6 h of 12), ABS an older one.
        val f = fixture(local = listOf(
            ProgressEntity("abs_1", 1 * HOUR, ABS_LENGTH, 1_000L, false),
            ProgressEntity("plex_1", 6 * HOUR, PLEX_LENGTH, 5_000L, false),
        ))
        val result = f.repo.syncCopies("srv_plex")
        assertEquals(1, result.booksChecked)
        assertEquals(1, result.copiesUpdated)
        assertEquals(ReadingProgress("abs_1", 5 * HOUR, ABS_LENGTH, 5_000L, isFinished = false, mirrored = true), f.row("abs_1"))
        assertEquals(6 * HOUR, f.row("plex_1")?.positionMs)
        val queued = f.pending.rows.value.single()
        assertEquals("abs_1", queued.bookId)
        assertEquals("srv_abs", queued.serverId)
        assertEquals(5 * HOUR, queued.positionMs)
        assertEquals(5_000L, queued.updatedAtMs)
    }

    @Test
    fun `cross sync works both ways`() = runTest {
        val f = fixture(local = listOf(
            ProgressEntity("abs_1", 4 * HOUR, ABS_LENGTH, 9_000L, false),
            ProgressEntity("plex_1", 1 * HOUR, PLEX_LENGTH, 2_000L, false),
        ))
        f.repo.syncCopies("srv_abs")
        assertEquals(ReadingProgress("plex_1", 4 * HOUR + 48 * 60_000L, PLEX_LENGTH, 9_000L, isFinished = false, mirrored = true), f.row("plex_1"))
        assertEquals("srv_plex", f.pending.rows.value.single().serverId)
    }

    @Test
    fun `cross sync fills a copy that has no row at all`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 3_000L, false)))
        f.repo.syncCopies()
        assertEquals(6 * HOUR, f.row("plex_1")?.positionMs)
        assertEquals(1, f.pending.rows.value.size)
    }

    @Test
    fun `cross sync of a finished book finishes the other copy`() = runTest {
        val f = fixture(local = listOf(
            ProgressEntity("abs_1", ABS_LENGTH, ABS_LENGTH, 8_000L, true),
            ProgressEntity("plex_1", 1 * HOUR, PLEX_LENGTH, 2_000L, false),
        ))
        f.repo.syncCopies()
        assertEquals(true, f.row("plex_1")?.isFinished)
        assertEquals(true, f.pending.rows.value.single().markedFinished)
    }

    @Test
    fun `cross sync twice changes nothing the second time`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 3_000L, false)))
        f.repo.syncCopies()
        val before = f.pending.rows.value
        val again = f.repo.syncCopies()
        assertEquals(before, f.pending.rows.value)
        // The mirror is only queued again (the same row, coalesced), never moved on.
        assertEquals(6 * HOUR, f.row("plex_1")?.positionMs)
        assertEquals(1, again.booksChecked)
    }

    @Test
    fun `cross sync leaves the loaded copy and books with no listening alone`() = runTest {
        val f = fixture(
            local = listOf(
                ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 3_000L, false),
                ProgressEntity("plex_1", 1 * HOUR, PLEX_LENGTH, 1_000L, false),
            ),
            loaded = "plex_1",
        )
        f.repo.syncCopies()
        assertEquals(1 * HOUR, f.row("plex_1")?.positionMs)
        assertTrue(f.pending.rows.value.isEmpty())

        val none = fixture()
        none.repo.syncCopies()
        assertNull(none.row("abs_1"))
        assertTrue(none.pending.rows.value.isEmpty())
    }

    @Test
    fun `cross sync without copies or with unreadable copies does nothing`() = runTest {
        val f = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 3_000L, false)))
        f.peers.groups = emptyMap()
        assertEquals(0, f.repo.syncCopies().booksChecked)
        val unreadable = object : ProgressMirrorPeers {
            override suspend fun peersOf(bookId: String): List<MirrorPeer> = emptyList()
            override suspend fun peersOfAll(bookIds: Collection<String>): Map<String, List<MirrorPeer>>? = null
        }
        val g = fixture(local = listOf(ProgressEntity("abs_1", 5 * HOUR, ABS_LENGTH, 3_000L, false)), peers = unreadable)
        assertEquals(0, g.repo.syncCopies().booksChecked)
        assertNull(g.row("plex_1"))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val ABS_LENGTH = 10 * HOUR
        const val PLEX_LENGTH = 12 * HOUR
        const val JF_LENGTH = 10 * HOUR - 4_000L
    }
}
