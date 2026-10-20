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
import com.chapterplayer.domain.playback.PendingProgress
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.playback.addToHistory
import com.chapterplayer.domain.playback.bookmarkTitleOrDefault
import com.chapterplayer.domain.playback.coalescePending
import com.chapterplayer.domain.playback.defaultBookmarkTitle
import com.chapterplayer.domain.playback.isFinishedAt
import com.chapterplayer.domain.playback.mergeBookmarks
import com.chapterplayer.domain.playback.mergeServerProgress
import com.chapterplayer.domain.playback.visibleBookmarks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

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
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() }
) : BookRepository {

    // -------------------------------------------------------------------------
    // Book observation (reads from local cache)
    // -------------------------------------------------------------------------

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
            // All fetching is done; every write below is one transaction, so a cancel or a crash keeps the old rows.
            inTransaction {
                // The library list carries no chapters: keep the ones details cached for books still on the server.
                val cached = bookDao.getByServer(server.id).associate { it.id to it.chaptersJson }
                val noChapters = ChapterJson.encode(emptyList())
                val books = incoming.map { row ->
                    val old = cached[row.id]
                    if (row.chaptersJson == noChapters && old != null) row.copy(chaptersJson = old) else row
                }
                bookDao.deleteByServer(server.id)
                bookDao.insertAll(books)
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
        }

    /** Failure messages name no token or address. */
    override suspend fun loadDetails(bookId: String): Result<BookDetails?> =
        try {
            val book = bookDao.getById(bookId) ?: error("Unknown book")
            val server = servers.getById(book.serverId) ?: error("The book's server is no longer configured")
            val source = sources.firstOrNull { it.type == server.source }
                ?: error("No library source for ${server.source}")
            val details = source.fetchBookDetails(server, bookId)
            if (details != null) bookDao.updateChapters(bookId, ChapterJson.encode(details.chapters))
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
            Result.success(source.startPlayback(server, bookId) ?: error("This server cannot play books yet"))
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

    override suspend fun syncPlayback(session: PlaybackSession, positionMs: Long, listenedMs: Long): Result<Unit> =
        try {
            val (server, source) = serverAndSource(session.serverId)
            Result.success(
                source.syncPlayback(
                    server, session.sessionId, positionMs.coerceAtLeast(0L), listenedMs.coerceAtLeast(0L), session.durationMs
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
                    isFinished = isFinishedAt(pending.positionMs, pending.durationMs)
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
        // Read, decide and write together, so a save from the player cannot slip in between.
        inTransaction {
            val merged = mergeServerProgress(
                bookId, progressDao.get(bookId)?.toDomain(), server, hasPendingLocal = pendingDao.get(bookId) != null
            )
            if (merged != null) progressDao.upsert(ProgressEntity.fromDomain(merged))
        }
    }

    override suspend fun mergeAllServerProgress(serverId: String) {
        try {
            val (server, source) = serverAndSource(serverId)
            val all = source.fetchAllProgress(server)
            if (all.isEmpty()) return
            val cached = bookDao.getByServer(serverId).map { it.id }.toSet()
            for ((bookId, progress) in all) {
                if (bookId in cached) saveServerProgress(bookId, progress)
            }
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

    override suspend fun pullBookmarks(serverId: String): Result<Unit> =
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
                        bookmarkDao.deleteForBook(bookId)
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

    override fun observePendingBookmarkCount(): Flow<Int> = bookmarkDao.observePendingCount()

    override suspend fun pushPendingBookmarks(serverId: String): Result<Unit> =
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
                source.createBookmark(server, row.bookId, row.timeSec, row.title)
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
                source.renameBookmark(server, row.bookId, row.timeSec, row.title)
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
