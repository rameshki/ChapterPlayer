package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.dto.AbsChapterDto
import com.chapterplayer.data.remote.abs.dto.AbsLibraryDto
import com.chapterplayer.data.remote.abs.dto.toBookDetails
import com.chapterplayer.data.remote.abs.dto.toPlaybackSession
import com.chapterplayer.data.remote.abs.dto.toBookCollection
import com.chapterplayer.data.remote.abs.fetchAllAbsCollections
import com.chapterplayer.data.remote.abs.fetchAllAbsItems
import com.chapterplayer.domain.browse.parseSeriesName
import com.chapterplayer.domain.playback.ServerProgress

/** The app's book ids for Audiobookshelf items start with this; the server only knows the part after it. */
internal const val ABS_ID_PREFIX = "abs_"

/** The server's own item id for an app book id: the one place the prefix is removed. */
internal fun String.toServerItemId(): String = removePrefix(ABS_ID_PREFIX)

/** Lists the books on an Audiobookshelf server. */
class AbsLibrarySource(
    private val api: AbsApiService,
    private val authorizer: AbsAuthorizer,
) : LibrarySource {

    override val type: ServerSource = ServerSource.ABS

    override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> =
        booksIn(server, libraryIdsFor(server))

    /** Asks the server for its libraries once and shares the answer between books, libraries and collections. */
    override suspend fun fetchSnapshot(server: ServerConfig): SourceSnapshot {
        // A configured library needs no list for its books; the list only names it, so its failure is not fatal then.
        val listed: List<AbsLibraryDto>? =
            if (server.libraryId.isNullOrBlank()) readLibraries(server) else fetchOrNull { readLibraries(server) }
        val ids = idsFrom(server, listed)
        val books = booksIn(server, ids)
        return SourceSnapshot(
            books = books,
            libraries = listed?.let { librariesOf(server, it) },
            collections = fetchOrNull { collectionsIn(server, ids) }
        )
    }

    private suspend fun booksIn(server: ServerConfig, libraryIds: List<String>): List<AudioBook> {
        return libraryIds.flatMap { libraryId ->
            val items = fetchAllAbsItems { page, limit ->
                authorizer.authorized(server) { bearerToken ->
                    api.getLibraryItems(
                        baseUrl = server.baseUrl,
                        bearerToken = bearerToken,
                        libraryId = libraryId,
                        limit = limit,
                        page = page
                    )
                }
            }
            items.map { item ->
                val metadata = item.media.metadata
                val series = parseSeriesName(metadata.seriesName)
                AudioBook(
                    id = ABS_ID_PREFIX + item.id,
                    title = metadata.title ?: "Unknown Title",
                    author = metadata.authorName ?: "Unknown Author",
                    narrator = metadata.narratorName ?: "",
                    durationMs = ((item.media.duration ?: 0.0) * 1000).toLong(),
                    chapters = item.media.chapters?.map { it.toChapter() } ?: emptyList(),
                    streamUrl = "${server.baseUrl}/api/items/${item.id}/play",
                    coverUrl = if (item.media.coverPath != null)
                        "${server.baseUrl}/api/items/${item.id}/cover"
                    else null,
                    source = ServerSource.ABS,
                    serverId = server.id,
                    libraryId = libraryId,
                    addedAt = item.addedAt ?: 0L,
                    seriesName = series?.name,
                    seriesSequence = series?.sequence
                )
            }
        }
    }

    override suspend fun fetchBookDetails(server: ServerConfig, bookId: String): BookDetails =
        authorizer.authorized(server) { bearerToken ->
            api.getItem(server.baseUrl, bearerToken, bookId.toServerItemId())
        }.toBookDetails()

    override suspend fun startPlayback(server: ServerConfig, bookId: String): PlaybackSession =
        authorizer.authorized(server) { bearerToken ->
            api.startPlayback(server.baseUrl, bearerToken, bookId.toServerItemId())
        }.toPlaybackSession(server.id, bookId, server.baseUrl)

    override suspend fun closePlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long) {
        authorizer.authorized(server) { bearerToken ->
            api.closeSession(server.baseUrl, bearerToken, sessionId, positionMs / 1000.0, listenedMs / 1000.0)
        }
    }

    override suspend fun syncPlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long, durationMs: Long) {
        authorizer.authorized(server) { bearerToken ->
            api.syncSession(server.baseUrl, bearerToken, sessionId, positionMs / 1000.0, listenedMs / 1000.0, durationMs / 1000.0)
        }
    }

    override suspend fun fetchProgress(server: ServerConfig, bookId: String): ServerProgress? =
        authorizer.authorized(server) { bearerToken ->
            api.getProgress(server.baseUrl, bearerToken, bookId.toServerItemId())
        }?.toServerProgress()

    override suspend fun pushProgress(server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean) {
        authorizer.authorized(server) { bearerToken ->
            api.patchProgress(server.baseUrl, bearerToken, bookId.toServerItemId(), positionMs / 1000.0, durationMs / 1000.0, isFinished)
        }
    }

    /** Book progress only: entries without an item id or for a podcast episode are skipped. */
    override suspend fun fetchAllProgress(server: ServerConfig): Map<String, ServerProgress> =
        authorizer.authorized(server) { bearerToken -> api.getMeProgress(server.baseUrl, bearerToken) }
            .filter { it.libraryItemId.isNotEmpty() && it.episodeId.isNullOrEmpty() }
            .associate { ABS_ID_PREFIX + it.libraryItemId to it.toServerProgress() }

    override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> =
        librariesOf(server, readLibraries(server))

    private fun librariesOf(server: ServerConfig, listed: List<AbsLibraryDto>): List<LibraryInfo> {
        val bookLibraries = listed.filter { it.mediaType == "book" }
        if (!server.libraryId.isNullOrBlank()) {
            val name = bookLibraries.firstOrNull { it.id == server.libraryId }?.name ?: server.libraryId
            return listOf(LibraryInfo(server.id, server.libraryId, name))
        }
        return bookLibraries.map { LibraryInfo(server.id, it.id, it.name) }
    }

    override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> =
        collectionsIn(server, libraryIdsFor(server))

    private suspend fun collectionsIn(server: ServerConfig, libraryIds: List<String>): List<BookCollection> =
        libraryIds.flatMap { libraryId ->
            fetchAllAbsCollections { page, limit ->
                authorizer.authorized(server) { bearerToken ->
                    api.getLibraryCollections(
                        baseUrl = server.baseUrl,
                        bearerToken = bearerToken,
                        libraryId = libraryId,
                        limit = limit,
                        page = page
                    )
                }
            }.map { it.toBookCollection(server.id) }
        }

    /** The configured library (unless turned off), or else every book library the user has not turned off. */
    private suspend fun libraryIdsFor(server: ServerConfig): List<String> =
        if (!server.libraryId.isNullOrBlank()) configuredIds(server) else idsFrom(server, readLibraries(server))

    private fun idsFrom(server: ServerConfig, listed: List<AbsLibraryDto>?): List<String> =
        if (!server.libraryId.isNullOrBlank()) {
            configuredIds(server)
        } else {
            requireNotNull(listed) { "The server's library list is missing" }
                .filter { it.mediaType == "book" && it.id !in server.excludedLibraryIds }
                .map { it.id }
        }

    /** The configured library is just another library: it is skipped when the user turned it off. */
    private fun configuredIds(server: ServerConfig): List<String> =
        listOf(server.libraryId!!).filter { it !in server.excludedLibraryIds }

    /** One `GET /api/libraries`. */
    private suspend fun readLibraries(server: ServerConfig): List<AbsLibraryDto> =
        authorizer.authorized(server) { bearerToken -> api.getLibraries(server.baseUrl, bearerToken) }.libraries

    private fun AbsChapterDto.toChapter() = Chapter(
        id = id,
        title = title,
        startMs = (start * 1000).toLong(),
        endMs = (end * 1000).toLong()
    )
}
