package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.CollectionDao
import com.chapterplayer.data.local.dao.LibraryDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.LibraryEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.source.LibrarySource
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
            Result.success(source.closePlayback(server, session.sessionId, positionMs, listenedMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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
