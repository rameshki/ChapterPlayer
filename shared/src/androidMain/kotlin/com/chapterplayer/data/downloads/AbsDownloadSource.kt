package com.chapterplayer.data.downloads

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.dto.toAudioFileEntries
import com.chapterplayer.data.remote.abs.dto.toBookDetails
import com.chapterplayer.data.source.toServerItemId
import com.chapterplayer.domain.downloads.planFiles
import io.ktor.http.HttpHeaders

/**
 * Audiobookshelf downloads: the files are the item's `audioFiles` (`GET /api/items/{id}`), each fetched from
 * `/api/items/{id}/file/{ino}/download` with the bearer token ([authorizer]: refreshed once on a 401).
 */
class AbsDownloadSource(
    private val api: AbsApiService,
    private val authorizer: AbsAuthorizer,
) : DownloadSource {

    override val type: ServerSource = ServerSource.ABS

    override suspend fun plan(server: ServerConfig, bookId: String): SourcePlan {
        val item = authorizer.authorized(server) { bearer -> api.getItem(server.baseUrl, bearer, bookId.toServerItemId()) }
        return SourcePlan(planFiles(item.toAudioFileEntries()), item.toBookDetails().chapters)
    }

    override suspend fun <T> authorized(server: ServerConfig, bookId: String, fileId: String, fetch: suspend (FileFetch) -> T): T {
        val url = AbsApiService.fileDownloadUrl(server.baseUrl, bookId.toServerItemId(), fileId)
        return authorizer.authorized(server) { bearer -> fetch(FileFetch(server, url, listOf(HttpHeaders.Authorization to bearer))) }
    }

    override suspend fun <T> authorizedSmall(server: ServerConfig, url: String, fetch: suspend (FileFetch) -> T): T =
        authorizer.authorized(server) { bearer -> fetch(FileFetch(server, url, listOf(HttpHeaders.Authorization to bearer))) }

    override suspend fun fileLengths(server: ServerConfig, bookId: String): Map<String, Long> {
        val item = authorizer.authorized(server) { bearer -> api.getItem(server.baseUrl, bearer, bookId.toServerItemId()) }
        return item.toAudioFileEntries()
            .mapNotNull { entry ->
                val ino = entry.ino?.trim().orEmpty()
                val ms = entry.durationMs ?: 0L
                if (ino.isNotEmpty() && ms > 0L) ino to ms else null
            }
            .toMap()
    }
}
