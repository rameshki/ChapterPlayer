package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.dto.PlexChapterDto

/** Lists the books on a Plex Media Server. */
class PlexLibrarySource(private val api: PlexApiService) : LibrarySource {

    override val type: ServerSource = ServerSource.PLEX

    override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> {
        // If a section ID was provided, use it.
        // Otherwise, fetch all music sections (audiobooks live in music libraries in Plex).
        val sectionIds: List<String> = if (!server.libraryId.isNullOrBlank()) {
            listOf(server.libraryId)
        } else {
            api.getLibrarySections(baseUrl = server.baseUrl, token = server.token)
                .mediaContainer
                .directories
                ?.filter { it.type == "artist" }   // "artist" == music/audiobook section in Plex
                ?.map { it.key }
                ?: emptyList()
        }

        return sectionIds.flatMap { sectionId ->
            val response = api.getLibraryAll(
                baseUrl = server.baseUrl,
                token = server.token,
                sectionId = sectionId
            )
            response.mediaContainer.metadata?.map { meta ->
                val streamPath = meta.media?.firstOrNull()?.parts?.firstOrNull()?.key ?: ""
                AudioBook(
                    id = "plex_${meta.ratingKey}",
                    title = meta.title,
                    author = meta.author ?: "Unknown Author",
                    narrator = "",
                    durationMs = meta.durationMs ?: 0L,
                    chapters = meta.chapters?.map { it.toChapter() } ?: emptyList(),
                    streamUrl = "${server.baseUrl}$streamPath?X-Plex-Token=${server.token}",
                    coverUrl = if (meta.thumb != null)
                        "${server.baseUrl}${meta.thumb}?X-Plex-Token=${server.token}"
                    else null,
                    source = ServerSource.PLEX,
                    serverId = server.id
                )
            } ?: emptyList()
        }
    }

    private fun PlexChapterDto.toChapter() = Chapter(
        id = id,
        title = title,
        startMs = startTimeMs,
        endMs = 0L
    )
}
