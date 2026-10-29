package com.chapterplayer.data.repository

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.BookmarkDao
import com.chapterplayer.data.local.dao.HistoryDao
import com.chapterplayer.data.local.dao.CollectionDao
import com.chapterplayer.data.local.dao.LibraryDao
import com.chapterplayer.data.local.dao.PendingProgressDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.BookmarkEntity
import com.chapterplayer.data.local.entity.HistoryEntity
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.LibraryEntity
import com.chapterplayer.data.local.entity.PendingProgressEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import com.chapterplayer.domain.playback.HistoryEntry
import com.chapterplayer.domain.playback.MarkOutcome
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.combinedMarkOutcome
import com.chapterplayer.domain.playback.mirroredProgress
import com.chapterplayer.domain.playback.mirrorRowYields
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.playback.addToHistory
import com.chapterplayer.domain.playback.bookmarkTitleOrDefault
import com.chapterplayer.domain.playback.coalescePending
import com.chapterplayer.domain.playback.defaultBookmarkTitle
import com.chapterplayer.domain.playback.isFinishedAt
import com.chapterplayer.domain.playback.mergeBookmarks
import com.chapterplayer.domain.playback.keepsFinishedMark
import com.chapterplayer.domain.playback.mergeServerProgress
import com.chapterplayer.domain.playback.progressAfterMark
import com.chapterplayer.domain.playback.visibleBookmarks
import com.chapterplayer.data.remote.HttpStatusException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a local copy of every server's books and the listening progress.
 * Books come from the [LibrarySource] that matches the server's type.
 */
class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val progressDao: ProgressDao,
    private val libraryDao: LibraryDao,
    private val collectionDao: CollectionDao,
    private val servers: ServerRepository,
    private val sources: List<LibrarySource>,
    private val pendingDao: PendingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val historyDao: HistoryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Runs the block as one database write transaction; the default runs it as is (for fakes). */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Called after a mark was queued, so the flusher can look at the queue. */
    private val onQueued: () -> Unit = {},
    /**
     * Called when starting playback failed: the server to retry once with (its address changed, e.g. a Plex server whose
     * cached connection no longer answers was switched to another one), or null to fail as is.
     */
    private val onConnectionFailure: suspend (ServerConfig, Throwable) -> ServerConfig? = { _, _ -> null },
    /** The other copies of a merged book, whose local progress follows each save (none: no mirroring). */
    private val mirrorPeers: ProgressMirrorPeers = ProgressMirrorPeers.None,
    /** The book loaded in the player, if any: a mirror never rewrites its row (the player's own saves own it). */
    private val loadedBookId: () -> String? = { null },
    /** How long a mark waits for a server before it is queued instead (the menu's message waits for it). */
    private val markServerWaitMs: Long = MARK_SERVER_WAIT_MS,
) : BookRepository {

    // -------------------------------------------------------------------------
    // Book observation (reads from local cache)
    // -------------------------------------------------------------------------

    override suspend fun fillUnknownDurations(durations: Map<String, Long>) {
        val known = durations.filterValues { it > 0L }
        if (known.isEmpty()) return
        // Read and written in one transaction, so a length the details stored meanwhile is never replaced.
        inTransaction {
            for ((id, length) in known) {
                val row = bookDao.getById(id) ?: continue
                if (row.durationMs <= 0L) bookDao.updateDuration(id, length)
            }
        }
    }

    override fun observeAllBooks(): Flow<List<AudioBook>> =
        bookDao.observeAll().map { entities -> entities.map { it.toAudioBook() } }

    override fun observeBooksByServer(serverId: String): Flow<List<AudioBook>> =
        bookDao.observeByServer(serverId).map { entities -> entities.map { it.toAudioBook() } }

    override fun observeCollections(): Flow<List<BookCollection>> =
        combine(collectionDao.observeCollections(), collectionDao.observeCollectionBooks()) { collections, links ->
            val booksByCollection = links.groupBy { it.serverId to it.collectionId }
            collections.map { collection ->
                val bookIds = booksByCollection[collection.serverId to collection.id].orEmpty()
                    .sortedBy { it.position }
                    .map { it.bookId }
                BookCollection(collection.serverId, collection.id, collection.name, bookIds)
            }
        }

    override fun observeLibraries(): Flow<List<LibraryInfo>> =
        libraryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    // -------------------------------------------------------------------------
    // Sync from network → update local cache
    // -------------------------------------------------------------------------

    /**
     * Fetches everything first and only then replaces the server's cached books, so a
     * failed fetch leaves the existing cache untouched. The server's libraries and collections are
     * replaced in the same pass, but a failure fetching either only keeps the old rows: it never fails the sync.
     * A library whose books could not be read (the others could) keeps its cached books; the books that were read are
     * stored, and the result is then a failure that names the libraries, so the sync shows it.
     */
    override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> =
        runCatching {
            val source = sources.firstOrNull { it.type == server.source }
                ?: error("No library source for ${server.source}")
            val now = clock()
            val snapshot = source.fetchSnapshot(server)
            val incoming = snapshot.books.map { BookEntity.fromDomain(it, cachedAt = now) }
            val libraries = snapshot.libraries
            val collections = snapshot.collections
            val failed = snapshot.failedLibraries
            // All fetching is done; every write below is one transaction, so a cancel or a crash keeps the old rows.
            inTransaction {
                val cachedRows = bookDao.getByServer(server.id)
                // The library list carries no chapters (and a Plex one no length): keep what details cached for books
                // still on the server.
                val cached = cachedRows.associateBy { it.id }
                val noChapters = ChapterJson.encode(emptyList())
                val books = incoming.map { row ->
                    val old = cached[row.id]
                    if (old == null) row else row.copy(
                        chaptersJson = if (row.chaptersJson == noChapters) old.chaptersJson else row.chaptersJson,
                        durationMs = if (row.durationMs <= 0L) old.durationMs else row.durationMs
                    )
                }
                // A library that could not be read keeps what was cached for it.
                val incomingIds = books.mapTo(HashSet()) { it.id }
                val kept = cachedRows.filter { it.libraryId in failed.keys && it.id !in incomingIds }
                bookDao.deleteByServer(server.id)
                bookDao.insertAll(books + kept)
                if (libraries != null) {
                    libraryDao.deleteForServer(server.id)
                    libraryDao.insertAll(libraries.map { LibraryEntity.fromDomain(it) })
                }
                if (collections != null) {
                    collectionDao.deleteCollectionsForServer(server.id)
                    collectionDao.deleteCollectionBooksForServer(server.id)
                    collectionDao.insertCollections(collections.map { CollectionEntity(it.serverId, it.id, it.name) })
                    collectionDao.insertCollectionBooks(
                        collections.flatMap { collection ->
                            collection.bookIds.mapIndexed { position, bookId ->
                                CollectionBookEntity(collection.serverId, collection.id, bookId, position)
                            }
                        }
                    )
                }
            }
            if (failed.isNotEmpty()) throw IllegalStateException(failed.values.joinToString("; "))
        }

    /** Failure messages name no token or address. */
    override suspend fun loadDetails(bookId: String): Result<BookDetails?> =
        try {
            val book = bookDao.getById(bookId) ?: error("Unknown book")
            val server = servers.getById(book.serverId) ?: error("The book's server is no longer configured")
            val source = sources.firstOrNull { it.type == server.source }
                ?: error("No library source for ${server.source}")
            val details = source.fetchBookDetails(server, bookId)
            if (details != null) {
                bookDao.updateChapters(bookId, ChapterJson.encode(details.chapters))
                details.durationMs?.takeIf { it > 0L }?.let { bookDao.updateDuration(bookId, it) }
            }
            Result.success(details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Failure messages name no token or address. */
    override suspend fun startPlayback(bookId: String): Result<PlaybackSession> =
        try {
            val book = bookDao.getById(bookId) ?: error("Unknown book")
            val server = servers.getById(book.serverId) ?: error("The book's server is no longer configured")
            val source = sources.firstOrNull { it.type == server.source }
                ?: error("No library source for ${server.source}")
            val session = try {
                source.startPlayback(server, bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retry = try {
                    onConnectionFailure(server, e)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
                if (retry == null || retry.baseUrl == server.baseUrl) throw e
                source.startPlayback(retry, bookId)
            }
            Result.success(session ?: error("This server cannot play books yet"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun closePlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit> =
        try {
            val server = servers.getById(session.serverId) ?: error("The book's server is no longer configured")
            val source = sources.firstOrNull { it.type == server.source }
                ?: error("No library source for ${server.source}")
            Result.success(source.closePlayback(server, session.sessionId, positionMs.coerceAtLeast(0L), listenedMs.coerceAtLeast(0L)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun syncPlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long, isPlaying: Boolean): Result<Unit> =
        try {
            val (server, source) = serverAndSource(session.serverId)
            Result.success(
                source.syncPlayback(
                    server, session.sessionId, positionMs.coerceAtLeast(0L), listenedMs.coerceAtLeast(0L), session.durationMs, isPlaying
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun fetchServerProgress(bookId: String): Result<ServerProgress?> =
        try {
            val book = bookDao.getById(bookId) ?: error("Unknown book")
            val (server, source) = serverAndSource(book.serverId)
            Result.success(source.fetchProgress(server, bookId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override suspend fun pushProgress(pending: PendingProgress): Result<Unit> =
        try {
            val (server, source) = serverAndSource(pending.serverId)
            Result.success(
                source.pushProgress(
                    server, pending.bookId, pending.positionMs, pending.durationMs,
                    isFinished = pending.markedFinished ?: isFinishedAt(pending.positionMs, pending.durationMs)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** The book's server and the source for its type; the messages name no token or address. */
    private suspend fun serverAndSource(serverId: String): Pair<ServerConfig, LibrarySource> {
        val server = servers.getById(serverId) ?: error("The book's server is no longer configured")
        val source = sources.firstOrNull { it.type == server.source } ?: error("No library source for ${server.source}")
        return server to source
    }

    // -------------------------------------------------------------------------
    // Pending progress queue
    // -------------------------------------------------------------------------

    override suspend fun queueProgress(pending: PendingProgress) {
        inTransaction {
            val existing = pendingDao.get(pending.bookId)
            val merged = coalescePending(existing?.toDomain(), pending)
            // A reset retry state starts at once; a kept one keeps its wait.
            val nextAttemptAtMs = if (merged.attempts == 0) 0L else existing?.nextAttemptAtMs ?: 0L
            pendingDao.upsert(PendingProgressEntity.fromDomain(merged, nextAttemptAtMs))
        }
    }

    override suspend fun pendingProgress(): List<PendingProgress> = pendingDao.getAll().map { it.toDomain() }

    override suspend fun deletePending(bookId: String, ifUpdatedAtMs: Long?) = pendingDao.delete(bookId, ifUpdatedAtMs)

    override suspend fun markPendingAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long?) =
        pendingDao.updateAttempt(bookId, attempts, nextAttemptAtMs, ifUpdatedAtMs)

    override suspend fun saveServerProgress(bookId: String, server: ServerProgress) {
        // A book that is not in the library gets no orphan progress row.
        if (bookDao.getById(bookId) == null) return
        saveServerProgress(bookId, server, peersOf(bookId))
    }

    /**
     * [saveServerProgress] with the other copies already looked up ([peers]). A queued mirror older than [server] is out of
     * date: it is removed and the merge goes ahead ([mirrorRowYields]); the book's own queued row or a mark holds it back.
     * The server's time is never later than now, and a mirrored row the server only echoes stays (see `mergeServerProgress`).
     */
    private suspend fun saveServerProgress(bookId: String, server: ServerProgress, peers: List<MirrorPeer>) {
        val loaded = loadedOrNull()
        // Read, decide and write together (the other copies' rows too), so a save from the player cannot slip in between.
        progressLock.withLock {
            inTransaction {
                val local = progressDao.get(bookId)?.toDomain()
                val queued = pendingDao.get(bookId)
                val blocked = when {
                    queued == null -> false
                    mirrorRowYields(queued.toDomain(), server) -> {
                        pendingDao.delete(bookId, queued.updatedAtMs)
                        false
                    }
                    else -> true
                }
                val merged = mergeServerProgress(bookId, local, server, hasPendingLocal = blocked, nowMs = clock())
                if (merged != null) {
                    progressDao.upsert(ProgressEntity.fromDomain(merged))
                    mirrorLocked(merged, peers, loaded)
                }
            }
        }
    }

    override suspend fun mergeAllServerProgress(serverId: String) {
        try {
            val (server, source) = serverAndSource(serverId)
            val all = source.fetchAllProgress(server)
            if (all.isEmpty()) return
            val cached = bookDao.getByServer(serverId).map { it.id }.toSet()
            // The other copies are looked up once for the whole pass.
            val ids = all.keys.filter { it in cached }
            val peers = try {
                mirrorPeers.peersOfAll(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            for (bookId in ids) saveServerProgress(bookId, all.getValue(bookId), peers[bookId].orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the library sync never fails over progress.
        }
    }

    // -------------------------------------------------------------------------
    // Bookmarks
    // -------------------------------------------------------------------------

    override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        bookmarkDao.observeForBook(bookId).map { rows -> visibleBookmarks(rows.map { it.toDomain() }) }

    override suspend fun addBookmark(bookId: String, positionMs: Long, title: String?): Bookmark {
        val book = bookDao.getById(bookId) ?: error("Unknown book")
        val pos = positionMs.coerceAtLeast(0L)
        val timeSec = pos / 1000
        val newTitle = bookmarkTitleOrDefault(title, pos)
        val now = clock()
        lateinit var saved: Bookmark
        inTransaction {
            val old = bookmarkDao.get(bookId, timeSec)?.toDomain()
            saved = when {
                old == null -> Bookmark(bookId, timeSec, newTitle, now, BookmarkState.PENDING_CREATE)
                // Not yet on the server: it stays a create, with the new title.
                old.state == BookmarkState.PENDING_CREATE -> old.copy(title = newTitle)
                // The server has one at this second (a row waiting to be deleted comes back): its title changes.
                else -> old.copy(title = newTitle, state = BookmarkState.PENDING_RENAME)
            }
            bookmarkDao.upsert(BookmarkEntity.fromDomain(saved, book.serverId))
        }
        return saved
    }

    override suspend fun renameBookmark(bookId: String, timeSec: Long, title: String) {
        inTransaction {
            val row = bookmarkDao.get(bookId, timeSec) ?: return@inTransaction
            val old = row.toDomain()
            if (old.state == BookmarkState.PENDING_DELETE) return@inTransaction
            val newTitle = title.trim().ifEmpty { defaultBookmarkTitle(timeSec * 1000) }
            val newState = if (old.state == BookmarkState.PENDING_CREATE) BookmarkState.PENDING_CREATE else BookmarkState.PENDING_RENAME
            bookmarkDao.upsert(row.copy(title = newTitle, state = newState.name))
        }
    }

    override suspend fun deleteBookmark(bookId: String, timeSec: Long) {
        inTransaction {
            val row = bookmarkDao.get(bookId, timeSec) ?: return@inTransaction
            when (row.toDomain().state) {
                // The server never heard of it: it must never reach the server.
                BookmarkState.PENDING_CREATE -> bookmarkDao.delete(bookId, timeSec)
                BookmarkState.SYNCED, BookmarkState.PENDING_RENAME ->
                    bookmarkDao.upsert(row.copy(state = BookmarkState.PENDING_DELETE.name))
                BookmarkState.PENDING_DELETE -> Unit
            }
        }
    }

    // A pull works from a snapshot of the server and a push changes what the server holds: they must not overlap, or the
    // pull could drop a row that was just pushed or bring back one that was just deleted. One lock for all servers is enough.
    private val bookmarkSyncLock = Mutex()

    override suspend fun pullBookmarks(serverId: String): Result<Unit> =
        bookmarkSyncLock.withLock { pullBookmarksLocked(serverId) }

    override suspend fun pushPendingBookmarks(serverId: String): Result<Unit> =
        bookmarkSyncLock.withLock { pushPendingBookmarksLocked(serverId) }

    private suspend fun pullBookmarksLocked(serverId: String): Result<Unit> =
        try {
            val (server, source) = serverAndSource(serverId)
            if (server.source != ServerSource.ABS) {
                Result.success(Unit)
            } else {
                val fromServer = source.fetchBookmarks(server).groupBy { it.bookId }
                // The fetch is done; the local rows are read inside each book's transaction, so an add made meanwhile is merged, not lost.
                val bookIds = (fromServer.keys + bookmarkDao.getByServer(serverId).map { it.bookId }).toSet()
                val cached = bookDao.getByServer(serverId).map { it.id }.toSet()
                for (bookId in bookIds) {
                    inTransaction {
                        val local = bookmarkDao.getByServer(serverId).filter { it.bookId == bookId }.map { it.toDomain() }
                        val merged = if (bookId in cached) {
                            mergeBookmarks(local, fromServer[bookId].orEmpty()).result
                        } else {
                            // Not in the library: no rows from the server, and the synced rows go; pending local changes stay.
                            local.filter { it.state != BookmarkState.SYNCED }
                        }
                        bookmarkDao.deleteForBook(bookId, serverId)
                        bookmarkDao.insertAll(merged.map { BookmarkEntity.fromDomain(it, serverId) })
                    }
                }
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    override fun observePendingBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao.observePending().map { rows -> rows.map { it.toDomain() } }

    private suspend fun pushPendingBookmarksLocked(serverId: String): Result<Unit> =
        try {
            val (server, source) = serverAndSource(serverId)
            if (server.source != ServerSource.ABS) return Result.success(Unit)
            var failure: Throwable? = null
            for (row in bookmarkDao.getPendingByServer(serverId)) {
                try {
                    pushOne(source, server, row)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SignInRequiredException) {
                    return Result.failure(e)
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
            }
            failure?.let { Result.failure(it) } ?: Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Delivers one row; the row is only settled if it still has the state and title it had when it was read. */
    private suspend fun pushOne(source: LibrarySource, server: ServerConfig, row: BookmarkEntity) {
        val synced = BookmarkState.SYNCED.name
        when (row.toDomain().state) {
            BookmarkState.PENDING_CREATE -> {
                if (!sendOrDropIfGone(row) { source.createBookmark(server, row.bookId, row.timeSec, row.title) }) return
                if (bookmarkDao.updateStateIf(row.bookId, row.timeSec, synced, row.state, row.title) == 0) {
                    val now = bookmarkDao.get(row.bookId, row.timeSec)
                    if (now == null) {
                        // Deleted while the create was on its way: the server has it now, so it goes there too.
                        source.deleteBookmark(server, row.bookId, row.timeSec)
                    } else if (now.state == BookmarkState.PENDING_CREATE.name) {
                        // Renamed while the create was on its way: the server has the old title.
                        bookmarkDao.updateStateIf(row.bookId, row.timeSec, BookmarkState.PENDING_RENAME.name, now.state, now.title)
                    }
                }
            }
            BookmarkState.PENDING_RENAME -> {
                if (!sendOrDropIfGone(row) { source.renameBookmark(server, row.bookId, row.timeSec, row.title) }) return
                bookmarkDao.updateStateIf(row.bookId, row.timeSec, synced, row.state, row.title)
            }
            BookmarkState.PENDING_DELETE -> {
                source.deleteBookmark(server, row.bookId, row.timeSec)
                if (bookmarkDao.deleteIf(row.bookId, row.timeSec, row.state, row.title) == 0) {
                    val now = bookmarkDao.get(row.bookId, row.timeSec)
                    if (now != null && now.state == BookmarkState.PENDING_RENAME.name) {
                        // Brought back while the delete was on its way: the server no longer has it, so it must be created again.
                        bookmarkDao.updateStateIf(row.bookId, row.timeSec, BookmarkState.PENDING_CREATE.name, now.state, now.title)
                    }
                }
            }
            BookmarkState.SYNCED -> Unit
        }
    }

    /**
     * Runs a create or rename. A 404 means the book is gone from the server, so retrying can never work: the row is dropped
     * (if it was not changed meanwhile) and false is returned. Any other failure is thrown.
     */
    private suspend fun sendOrDropIfGone(row: BookmarkEntity, send: suspend () -> Unit): Boolean =
        try {
            send()
            true
        } catch (e: HttpStatusException) {
            if (e.status != HttpStatusCode.NotFound) throw e
            bookmarkDao.deleteIf(row.bookId, row.timeSec, row.state, row.title)
            false
        }

    // -------------------------------------------------------------------------
    // History
    // -------------------------------------------------------------------------

    override fun observeHistory(bookId: String): Flow<List<HistoryEntry>> =
        historyDao.observeForBook(bookId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun recordHistory(entry: HistoryEntry) {
        inTransaction {
            val existing = historyDao.getForBook(entry.bookId)
            val kept = addToHistory(existing.map { it.toDomain() }, entry).drop(1)
            // The kept entries are the old ones in the same order, so walk both lists together to find the dropped rows.
            var next = 0
            val dropped = mutableListOf<Long>()
            for (row in existing) {
                if (next < kept.size && row.toDomain() == kept[next]) next++ else dropped += row.id
            }
            if (dropped.isNotEmpty()) historyDao.deleteByIds(dropped)
            historyDao.insert(HistoryEntity.fromDomain(entry))
        }
    }

    // -------------------------------------------------------------------------
    // Progress
    // -------------------------------------------------------------------------

    override fun observeProgress(bookId: String): Flow<ReadingProgress?> =
        progressDao.observe(bookId).map { it?.toDomain() }

    override fun observeInProgressBooks(): Flow<List<ReadingProgress>> =
        progressDao.observeInProgress().map { entities -> entities.map { it.toDomain() } }

    override fun observeAllProgress(): Flow<List<ReadingProgress>> =
        progressDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveProgress(progress: ReadingProgress) {
        progressDao.upsert(ProgressEntity.fromDomain(progress))
    }

    // The player's save and a mark both read the row and write it back: one lock, so neither can overwrite the other from a stale read.
    private val progressLock = Mutex()

    /** Book id to the time of the finished mark the user made in this session (or of the last save that kept it). */
    private val userMarks = HashMap<String, Long>()

    override suspend fun savePlaybackProgress(progress: ReadingProgress, loadedSinceMs: Long) {
        val peers = peersOf(progress.bookId)
        val loaded = loadedOrNull()
        progressLock.withLock {
            inTransaction {
                val existing = progressDao.get(progress.bookId)?.toDomain()
                val keep = keepsFinishedMark(existing, userMarks[progress.bookId], loadedSinceMs)
                if (keep) userMarks[progress.bookId] = progress.lastPlayedAt else userMarks.remove(progress.bookId)
                // The player's save is listening on this copy: never a mirrored row.
                val saved = (if (keep) progress.copy(isFinished = true) else progress).copy(mirrored = false)
                progressDao.upsert(ProgressEntity.fromDomain(saved))
                mirrorLocked(saved, peers, loaded)
            }
        }
    }

    /** The other copies of [bookId] (empty with mirroring off); looked up before the lock, as it reads other tables. */
    private suspend fun peersOf(bookId: String): List<MirrorPeer> = try {
        mirrorPeers.peersOf(bookId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun loadedOrNull(): String? = try {
        loadedBookId()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Moves the other copies' local rows to where [source] is (see `mirroredProgress`: by fraction, newest wins, flagged
     * mirrored). Never the copy [loaded] in the player (its own saves own its row), and never a copy whose row is the
     * user's finished mark of this session (the player's pin, `keepsFinishedMark`, would be lost). Call with
     * [progressLock] held, inside the transaction that wrote [source].
     */
    private suspend fun mirrorLocked(source: ReadingProgress, peers: List<MirrorPeer>, loaded: String?) {
        for (peer in peers) {
            if (peer.bookId == loaded) continue
            val existing = progressDao.get(peer.bookId)?.toDomain()
            val pinned = userMarks[peer.bookId]?.let { at -> existing != null && existing.isFinished && existing.lastPlayedAt == at } == true
            if (pinned) continue
            val mirror = mirroredProgress(source, peer.bookId, peer.durationMs, existing) ?: continue
            progressDao.upsert(ProgressEntity.fromDomain(mirror))
        }
    }

    /**
     * Marks [bookId] and, for a merged book with mirroring on, every other copy too, all at once: each copy changes locally
     * first and goes to its own server (Audiobookshelf progress, Plex scrobbles); a server that does not answer within
     * [MARK_SERVER_WAIT_MS] gets the mark through the queue. The book's own failure is the result; otherwise the outcome
     * is PARTIAL when another copy could not be marked, QUEUED when any copy waits (see [combinedMarkOutcome]).
     */
    override suspend fun markFinished(bookId: String, finished: Boolean): Result<MarkOutcome> = coroutineScope {
        val copies = peersOf(bookId)
        val own = async { markOne(bookId, finished) }
        val others = copies.map { peer -> async { markOne(peer.bookId, finished).getOrNull() } }
        val first = own.await()
        val rest = others.awaitAll()
        val outcome = first.getOrNull() ?: return@coroutineScope first
        Result.success(combinedMarkOutcome(listOf(outcome) + rest))
    }

    private suspend fun markOne(bookId: String, finished: Boolean): Result<MarkOutcome> =
        try {
            val book = bookDao.getById(bookId) ?: error("Unknown book")
            val now = clock()
            // Local first: the UI follows at once and it works offline.
            lateinit var local: ReadingProgress
            progressLock.withLock {
                inTransaction {
                    local = progressAfterMark(progressDao.get(bookId)?.toDomain(), bookId, finished, book.durationMs, now)
                    progressDao.upsert(ProgressEntity.fromDomain(local))
                    if (finished) userMarks[bookId] = now else userMarks.remove(bookId)
                }
            }
            val target = try {
                // Audiobookshelf takes the mark as progress; Plex as a scrobble (or unscrobble) of every track.
                serverAndSource(book.serverId).takeIf { (server, _) -> server.source == ServerSource.ABS || server.source == ServerSource.PLEX }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (target == null) {
                Result.success(MarkOutcome.LOCAL_ONLY)
            } else {
                val pending = PendingProgress(
                    bookId, book.serverId, local.positionMs, local.durationMs, listenedMs = 0L, updatedAtMs = now, markedFinished = finished
                )
                if (withServerWait { pushProgress(pending) }?.isSuccess == true) {
                    // The mark replaces older queued positions of the book (a newer one is kept and delivered after it).
                    inTransaction {
                        val queued = pendingDao.get(bookId)
                        if (queued != null && queued.updatedAtMs <= now) pendingDao.delete(bookId, queued.updatedAtMs)
                    }
                    adoptServerAnswer(bookId, local)
                    Result.success(MarkOutcome.SYNCED)
                } else {
                    queueProgress(pending)
                    onQueued()
                    Result.success(MarkOutcome.QUEUED)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * After the server took a mark, reads what it holds (an un-finished book is reset to 0 there, whatever was sent) and stores
     * it, unless the progress changed again meanwhile. A failed or empty read keeps the local mark.
     */
    private suspend fun adoptServerAnswer(bookId: String, mark: ReadingProgress) {
        val answer = withServerWait { fetchServerProgress(bookId) }?.getOrNull() ?: return
        progressLock.withLock {
            inTransaction {
                val current = progressDao.get(bookId)
                if (current == null || current.lastPlayedAt <= mark.lastPlayedAt) {
                    progressDao.upsert(
                        ProgressEntity.fromDomain(
                            ReadingProgress(
                                bookId, answer.positionMs, answer.durationMs.takeIf { it > 0L } ?: mark.durationMs,
                                mark.lastPlayedAt, answer.isFinished
                            )
                        )
                    )
                    if (!answer.isFinished) userMarks.remove(bookId)
                }
            }
        }
    }

    /** [call] on the wall clock for at most [markServerWaitMs]; null when the server took longer. */
    private suspend fun <T> withServerWait(call: suspend () -> T): T? =
        withContext(Dispatchers.Default) { withTimeoutOrNull(markServerWaitMs) { call() } }

    private companion object {
        const val MARK_SERVER_WAIT_MS = 8_000L
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun BookEntity.toAudioBook(): AudioBook {
        val chapters = try {
            ChapterJson.decode(chaptersJson)
        } catch (e: Exception) {
            emptyList()
        }
        return toDomain(chapters)
    }
}
