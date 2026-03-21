package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.dto.AbsChapterDto
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.dto.PlexChapterDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val progressDao: ProgressDao,
    private val absApiService: AbsApiService,
    private val plexApiService: PlexApiService,
    private val moshi: Moshi
) : BookRepository {

    // Moshi adapters for serialising chapters to/from JSON for Room storage
    private val chapterListType = Types.newParameterizedType(List::class.java, Chapter::class.java)
    private val chapterAdapter get() = moshi.adapter<List<Chapter>>(chapterListType)

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

    override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> =
        runCatching {
            val books = when (server.source) {
                ServerSource.ABS -> fetchFromAbs(server)
                ServerSource.PLEX -> fetchFromPlex(server)
            }
            // Replace all books from this server with fresh data
            bookDao.deleteByServer(server.id)
            bookDao.insertAll(books)
        }

    // -------------------------------------------------------------------------
    // ABS fetching
    // -------------------------------------------------------------------------

    private suspend fun fetchFromAbs(server: ServerConfig): List<BookEntity> {
        val libraryId = requireNotNull(server.libraryId) {
            "Cannot sync ABS books: no library selected for server ${server.name}"
        }
        val bearerToken = "Bearer ${server.token}"
        val response = absApiService.getLibraryItems(
            bearerToken = bearerToken,
            libraryId = libraryId
        )
        val now = System.currentTimeMillis()
        return response.results.map { item ->
            val metadata = item.media.metadata
            val chapters = item.media.chapters?.mapIndexed { index, dto ->
                dto.toChapter(index)
            } ?: emptyList()
            BookEntity(
                id = "abs_${item.id}",
                title = metadata.title ?: "Unknown Title",
                author = metadata.authorName ?: "Unknown Author",
                narrator = metadata.narratorName ?: "",
                durationMs = ((item.media.duration ?: 0.0) * 1000).toLong(),
                chaptersJson = chapterAdapter.toJson(chapters),
                streamUrl = "${server.baseUrl}/api/items/${item.id}/play",
                coverUrl = if (item.media.coverPath != null) "${server.baseUrl}/api/items/${item.id}/cover" else null,
                source = ServerSource.ABS.name,
                serverId = server.id,
                cachedAt = now
            )
        }
    }

    // -------------------------------------------------------------------------
    // Plex fetching
    // -------------------------------------------------------------------------

    private suspend fun fetchFromPlex(server: ServerConfig): List<BookEntity> {
        val sectionId = requireNotNull(server.libraryId) {
            "Cannot sync Plex books: no library section selected for server ${server.name}"
        }
        val response = plexApiService.getLibraryAll(
            token = server.token,
            sectionId = sectionId
        )
        val now = System.currentTimeMillis()
        return response.mediaContainer.metadata?.mapIndexed { _, meta ->
            val chapters = meta.chapters?.map { it.toChapter() } ?: emptyList()
            val streamPath = meta.media?.firstOrNull()?.parts?.firstOrNull()?.key ?: ""
            BookEntity(
                id = "plex_${meta.ratingKey}",
                title = meta.title,
                author = meta.author ?: "Unknown Author",
                narrator = "",  // Plex doesn't expose narrator as a first-class field
                durationMs = meta.durationMs ?: 0L,
                chaptersJson = chapterAdapter.toJson(chapters),
                streamUrl = "${server.baseUrl}$streamPath?X-Plex-Token=${server.token}",
                coverUrl = if (meta.thumb != null) "${server.baseUrl}${meta.thumb}?X-Plex-Token=${server.token}" else null,
                source = ServerSource.PLEX.name,
                serverId = server.id,
                cachedAt = now
            )
        } ?: emptyList()
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
            chapterAdapter.fromJson(chaptersJson) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return toDomain(chapters)
    }

    private fun AbsChapterDto.toChapter(index: Int) = Chapter(
        id = id,
        title = title,
        startMs = (start * 1000).toLong(),
        endMs = (end * 1000).toLong()
    )

    private fun PlexChapterDto.toChapter() = Chapter(
        id = id,
        title = title,
        startMs = startTimeMs,
        endMs = 0L  // Plex doesn't always provide endTime; we'll calculate from next chapter's start
    )
}
