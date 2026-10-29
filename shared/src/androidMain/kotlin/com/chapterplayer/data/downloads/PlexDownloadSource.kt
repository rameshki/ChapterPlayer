package com.chapterplayer.data.downloads

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.source.PlexLibrarySource
import com.chapterplayer.domain.plex.PLEX_SIZE_UNKNOWN_TEXT
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.domain.plex.PlexDownloadPlan
import com.chapterplayer.domain.plex.plexDownloadPlan
import com.chapterplayer.domain.plex.plexSizeFromAnswer
import com.chapterplayer.domain.plex.plexUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plex downloads: one file per track of the album ([PlexLibrarySource.fetchContent], in play order), its id the track's
 * rating key (kept in the `ino` column of `download_files`), fetched from the part's path under the server's address
 * in use ([servers] is read for every request, so a download follows a change of connection) with the `X-Plex-Token`
 * header; no token is ever in an address. A 401 blanks the token and is "Sign in again" ([PlexLibrarySource.signedIn]).
 * A track whose size Plex leaves out is asked for its first byte before the plan is written; when that answer has no
 * size either, the book cannot be downloaded ([PLEX_SIZE_UNKNOWN_TEXT]).
 *
 * @param client the download client (no redirects followed), for those size requests.
 * @param onConnectionFailure the connection manager's re-probe: the server to try again with, or null.
 */
class PlexDownloadSource(
    private val plex: PlexLibrarySource,
    private val servers: ServerRepository,
    private val client: HttpClient,
    private val onConnectionFailure: suspend (ServerConfig, Throwable) -> ServerConfig? = { _, _ -> null },
    private val sizeTimeoutMs: Long = 30_000,
) : DownloadSource {

    override val type: ServerSource = ServerSource.PLEX

    /** Each book's part paths by rating key, from its last plan (or a later read of its tracks). */
    private val partsGuard = Mutex()
    private val parts = HashMap<String, Map<String, String>>()

    override suspend fun plan(server: ServerConfig, bookId: String): SourcePlan {
        val current = current(server)
        val content = plex.signedIn(current) { plex.fetchContent(current, bookId) }
        val files = when (val plan = plexDownloadPlan(content.tracks)) {
            is PlexDownloadPlan.Unavailable -> return SourcePlan(emptyList(), emptyList(), plan.reason)
            is PlexDownloadPlan.Files -> plan.files
        }
        remember(bookId, files.associate { it.file.ino to it.partKey })
        val sized = files.map { planned ->
            if (planned.file.totalBytes > 0L) {
                planned.file
            } else {
                val size = sizeOf(current, planned.partKey) ?: return SourcePlan(emptyList(), emptyList(), PLEX_SIZE_UNKNOWN_TEXT)
                planned.file.copy(totalBytes = size)
            }
        }
        return SourcePlan(sized, content.chapters)
    }

    override suspend fun <T> authorized(server: ServerConfig, bookId: String, fileId: String, fetch: suspend (FileFetch) -> T): T {
        val current = current(server)
        val partKey = partKeyOf(current, bookId, fileId) ?: throw DownloadFileGoneException()
        return plex.signedIn(current) { fetch(FileFetch(current, plexUrl(current.baseUrl, partKey), tokenHeader(current))) }
    }

    override suspend fun <T> authorizedSmall(server: ServerConfig, url: String, fetch: suspend (FileFetch) -> T): T {
        val current = current(server)
        return plex.signedIn(current) { fetch(FileFetch(current, url, tokenHeader(current))) }
    }

    override suspend fun afterConnectionFailure(used: ServerConfig, error: Throwable): ServerConfig? = onConnectionFailure(used, error)

    override suspend fun fileLengths(server: ServerConfig, bookId: String): Map<String, Long> {
        val current = current(server)
        val tracks = plex.signedIn(current) { plex.fetchContent(current, bookId) }.tracks
        return tracks.filter { it.durationMs > 0L }.associate { it.ratingKey to it.durationMs }
    }

    /** The server as saved now (its address may have changed since the job started); signed out = "Sign in again". */
    private suspend fun current(server: ServerConfig): ServerConfig {
        val now = servers.getById(server.id) ?: server
        if (now.token.isBlank()) throw SignInRequiredException(now.name)
        return now
    }

    private fun tokenHeader(server: ServerConfig) = listOf(PLEX_TOKEN_HEADER to server.token)

    private suspend fun remember(bookId: String, byKey: Map<String, String>) = partsGuard.withLock { parts[bookId] = byKey }

    /** The part path of [ratingKey]; the tracks are read again when it is not known (after a restart) or gone. */
    private suspend fun partKeyOf(server: ServerConfig, bookId: String, ratingKey: String): String? {
        partsGuard.withLock { parts[bookId]?.get(ratingKey) }?.let { return it }
        val content = plex.signedIn(server) { plex.fetchContent(server, bookId) }
        val files = (plexDownloadPlan(content.tracks) as? PlexDownloadPlan.Files)?.files ?: return null
        val byKey = files.associate { it.file.ino to it.partKey }
        remember(bookId, byKey)
        return byKey[ratingKey]
    }

    /** Asks for the part's first byte and reads its size from the answer; null when the answer has none. */
    private suspend fun sizeOf(server: ServerConfig, partKey: String): Long? = plex.signedIn(server) {
        withTimeoutOrNull(sizeTimeoutMs) {
            client.prepareGet(plexUrl(server.baseUrl, partKey)) {
                header(PLEX_TOKEN_HEADER, server.token)
                header(HttpHeaders.Range, "bytes=0-0")
            }.execute { response ->
                if (response.status == HttpStatusCode.Unauthorized) throw HttpStatusException(response.status)
                // The body is not read: leaving the block closes the request.
                plexSizeFromAnswer(response.status.value, response.headers[HttpHeaders.ContentRange], response.headers[HttpHeaders.ContentLength])
            }
        }
    }
}
