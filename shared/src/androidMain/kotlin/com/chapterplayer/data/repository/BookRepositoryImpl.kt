package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.dto.AbsChapterDto
import com.chapterplayer.data.remote.abs.fetchAllAbsItems
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.dto.PlexChapterDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val progressDao: ProgressDao,
    private val absApi: AbsApiService,
    private val plexApi: PlexApiService
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

    override suspend fun syncBooksFromServer(server: ServerConfig): Result<Unit> =
        runCatching {
            val books = when (server.source) {
                ServerSource.ABS -> fetchFromAbs(server)
                ServerSource.PLEX -> fetchFromPlex(server)
            }
            bookDao.deleteByServer(server.id)
            bookDao.insertAll(books)
        }

    // -------------------------------------------------------------------------
    // ABS fetching
    // -------------------------------------------------------------------------

    private suspend fun fetchFromAbs(server: ServerConfig): List<BookEntity> {
        val bearerToken = "Bearer ${server.token}"
        val now = System.currentTimeMillis()

        // If a specific library ID was provided, use it.
        // Otherwise, auto-discover all book-type libraries on this server.
        val libraryIds: List<String> = if (!server.libraryId.isNullOrBlank()) {
            listOf(server.libraryId)
        } else {
            absApi.getLibraries(server.baseUrl, bearerToken)
                .libraries
                .filter { it.mediaType == "book" }
                .map { it.id }
        }

        return libraryIds.flatMap { libraryId ->
            val items = fetchAllAbsItems { page, limit ->
                absApi.getLibraryItems(
                    baseUrl = server.baseUrl,
                    bearerToken = bearerToken,
                    libraryId = libraryId,
                    limit = limit,
                    page = page
                )
            }
            items.map { item ->
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
                    chaptersJson = ChapterJson.encode(chapters),
                    streamUrl = "${server.baseUrl}/api/items/${item.id}/play",
                    coverUrl = if (item.media.coverPath != null)
                        "${server.baseUrl}/api/items/${item.id}/cover"
                    else null,
                    source = ServerSource.ABS.name,
                    serverId = server.id,
                    cachedAt = now
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Plex fetching
    // -------------------------------------------------------------------------

    private suspend fun fetchFromPlex(server: ServerConfig): List<BookEntity> {
        val now = System.currentTimeMillis()

        // If a section ID was provided, use it.
        // Otherwise, fetch all music sections (audiobooks live in music libraries in Plex).
        val sectionIds: List<String> = if (!server.libraryId.isNullOrBlank()) {
            listOf(server.libraryId)
        } else {
            plexApi.getLibrarySections(baseUrl = server.baseUrl, token = server.token)
                .mediaContainer
                .directories
                ?.filter { it.type == "artist" }   // "artist" == music/audiobook section in Plex
                ?.map { it.key }
                ?: emptyList()
        }

        return sectionIds.flatMap { sectionId ->
            val response = plexApi.getLibraryAll(
                baseUrl = server.baseUrl,
                token = server.token,
                sectionId = sectionId
            )
            response.mediaContainer.metadata?.map { meta ->
                val chapters = meta.chapters?.map { it.toChapter() } ?: emptyList()
                val streamPath = meta.media?.firstOrNull()?.parts?.firstOrNull()?.key ?: ""
                BookEntity(
                    id = "plex_${meta.ratingKey}",
                    title = meta.title,
                    author = meta.author ?: "Unknown Author",
                    narrator = "",
                    durationMs = meta.durationMs ?: 0L,
                    chaptersJson = ChapterJson.encode(chapters),
                    streamUrl = "${server.baseUrl}$streamPath?X-Plex-Token=${server.token}",
                    coverUrl = if (meta.thumb != null)
                        "${server.baseUrl}${meta.thumb}?X-Plex-Token=${server.token}"
                    else null,
                    source = ServerSource.PLEX.name,
                    serverId = server.id,
                    cachedAt = now
                )
            } ?: emptyList()
        }
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
        endMs = 0L
    )
}
