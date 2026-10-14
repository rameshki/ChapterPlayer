package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.source.LibrarySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Keeps a local copy of every server's books and the listening progress.
 * Books come from the [LibrarySource] that matches the server's type.
 */
class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val progressDao: ProgressDao,
    private val sources: List<LibrarySource>,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : BookRepository {

    // -------------------------------------------------------------------------
    // Book observation (reads from local cache)
    // -------------------------------------------------------------------------

    override fun observeAllBooks(): Flow<List<AudioBook>> =
        bookDao.observeAll().map { entities -> entities.map { it.toAudioBook() } }

    override fun observeBooksByServer(serverId: String): Flow<List<AudioBook>> =
        bookDao.observeByServer(serverId).map { entities -> entities.map { it.toAudioBook() } }

    // -------------------------------------------------------------------------
    // Sync from network → update local cache
    // -------------------------------------------------------------------------

    /**
     * Fetches everything first and only then replaces the server's cached books, so a
     * failed fetch leaves the existing cache untouched.
     */
    override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> =
        runCatching {
            val source = sources.firstOrNull { it.type == server.source }
                ?: error("No library source for ${server.source}")
            val now = clock()
            val books = source.fetchBooks(server).map { BookEntity.fromDomain(it, cachedAt = now) }
            bookDao.deleteByServer(server.id)
            bookDao.insertAll(books)
        }

    // -------------------------------------------------------------------------
    // Progress
    // -------------------------------------------------------------------------

    override fun observeProgress(bookId: String): Flow<ReadingProgress?> =
        progressDao.observe(bookId).map { it?.toDomain() }

    override fun observeInProgressBooks(): Flow<List<ReadingProgress>> =
        progressDao.observeInProgress().map { entities -> entities.map { it.toDomain() } }

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
