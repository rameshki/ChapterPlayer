package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.dto.AbsChapterDto
import com.chapterplayer.data.remote.abs.fetchAllAbsItems

/** Lists the books on an Audiobookshelf server. */
class AbsLibrarySource(private val api: AbsApiService) : LibrarySource {

    override val type: ServerSource = ServerSource.ABS

    override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> {
        val bearerToken = "Bearer ${server.token}"

        // If a specific library ID was provided, use it.
        // Otherwise, auto-discover all book-type libraries on this server.
        val libraryIds: List<String> = if (!server.libraryId.isNullOrBlank()) {
            listOf(server.libraryId)
        } else {
            api.getLibraries(server.baseUrl, bearerToken)
                .libraries
                .filter { it.mediaType == "book" }
                .map { it.id }
        }

        return libraryIds.flatMap { libraryId ->
            val items = fetchAllAbsItems { page, limit ->
                api.getLibraryItems(
                    baseUrl = server.baseUrl,
                    bearerToken = bearerToken,
                    libraryId = libraryId,
                    limit = limit,
                    page = page
                )
            }
            items.map { item ->
                val metadata = item.media.metadata
                AudioBook(
                    id = "abs_${item.id}",
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
                    serverId = server.id
                )
            }
        }
    }

    private fun AbsChapterDto.toChapter() = Chapter(
        id = id,
        title = title,
        startMs = (start * 1000).toLong(),
        endMs = (end * 1000).toLong()
    )
}
