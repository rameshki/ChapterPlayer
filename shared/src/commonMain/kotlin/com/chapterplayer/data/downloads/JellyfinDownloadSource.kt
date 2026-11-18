package com.chapterplayer.data.downloads

import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.playback.jellyfinStreamHeader
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.domain.jellyfin.JellyfinDownloadPlan
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import com.chapterplayer.domain.jellyfin.jellyfinDownloadPlan
import com.chapterplayer.domain.jellyfin.jellyfinDownloadUrl
import com.chapterplayer.domain.jellyfin.jellyfinItemId
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Jellyfin downloads (J3, review R39): a Jellyfin audiobook is one file, so a book is one planned file whose id is the item
 * id (the same id the playback track carries, so a downloaded book plays from the phone). It is read from
 * `GET /Items/{id}/Download` (the original file, with `Range` for resume; the real server answers 206) with the
 * `Authorization: MediaBrowser ...` header from [header], never a token in an address. Jellyfin tokens have no refresh: a
 * 401 blanks the token ([markSignedOut]) and is "Sign in again". A 403 (the account may not download) and a 404 are
 * handled by the downloader as for the other kinds. Covers are anonymous and are fetched with no credential.
 *
 * @param markSignedOut blanks the server's token if it is still the one the server refused.
 */
class JellyfinDownloadSource(
    private val api: JellyfinApiService,
    private val installId: InstallId,
    appInfo: AppInfo,
    private val markSignedOut: suspend (ServerConfig) -> Unit = {},
) : DownloadSource {

    /** The `Authorization: MediaBrowser ...` value of a request to a server: the row's DeviceId and token, as streaming builds it. */
    private val header: suspend (ServerConfig) -> String = jellyfinStreamHeader(installId, appInfo)

    override val type: ServerSource = ServerSource.JELLYFIN

    override suspend fun plan(server: ServerConfig, bookId: String): SourcePlan {
        val itemId = jellyfinItemId(bookId) ?: return SourcePlan(emptyList(), emptyList(), failure = "This is not a Jellyfin book")
        val item = signedIn(server) { api.getItem(server.baseUrl, deviceId(server), server.token, itemId) }
        return when (val plan = jellyfinDownloadPlan(itemId, item)) {
            is JellyfinDownloadPlan.Ready -> SourcePlan(listOf(plan.file), plan.chapters)
            is JellyfinDownloadPlan.Refused -> SourcePlan(emptyList(), emptyList(), failure = plan.text)
        }
    }

    override suspend fun <T> authorized(server: ServerConfig, bookId: String, fileId: String, fetch: suspend (FileFetch) -> T): T {
        val itemId = jellyfinItemId(bookId)
        if (itemId == null || itemId != fileId) throw DownloadFileGoneException()
        if (server.token.isBlank()) throw SignInRequiredException(server.name)
        val request = FileFetch(server, jellyfinDownloadUrl(server.baseUrl, itemId), listOf(HttpHeaders.Authorization to header(server)))
        return signedIn(server) { fetch(request) }
    }

    /** A cover (anonymous on Jellyfin): fetched with no credential at all. */
    override suspend fun <T> authorizedSmall(server: ServerConfig, url: String, fetch: suspend (FileFetch) -> T): T =
        fetch(FileFetch(server, url, emptyList()))

    override suspend fun fileLengths(server: ServerConfig, bookId: String): Map<String, Long> {
        val itemId = jellyfinItemId(bookId) ?: return emptyMap()
        val item = signedIn(server) { api.getItem(server.baseUrl, deviceId(server), server.token, itemId) }
        val plan = jellyfinDownloadPlan(itemId, item) as? JellyfinDownloadPlan.Ready ?: return emptyMap()
        return mapOf(plan.file.ino to plan.file.durationMs)
    }

    private fun deviceId(server: ServerConfig) = jellyfinDeviceId(installId.value, server.id)

    /** Runs [call]; a 401 blanks the server's token and is a [SignInRequiredException] (Jellyfin tokens have no refresh). */
    private suspend fun <T> signedIn(server: ServerConfig, call: suspend () -> T): T =
        try {
            call()
        } catch (e: HttpStatusException) {
            if (e.status.value != 401) throw e
            try {
                withContext(NonCancellable) { markSignedOut(server) }
            } catch (_: Exception) {
                // Best effort: the caller still learns that the sign-in is gone.
            }
            throw SignInRequiredException(server.name)
        }
}
