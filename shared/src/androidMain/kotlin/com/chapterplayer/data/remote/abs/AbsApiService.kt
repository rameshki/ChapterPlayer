package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.dto.AbsBookmarkDto
import com.chapterplayer.data.remote.abs.dto.AbsBookmarkRequest
import com.chapterplayer.data.remote.abs.dto.AbsCloseSessionRequest
import com.chapterplayer.data.remote.abs.dto.AbsMeBookmarksDto
import com.chapterplayer.data.remote.abs.dto.AbsCollectionsResponse
import com.chapterplayer.data.remote.abs.dto.AbsItemDetailDto
import com.chapterplayer.data.remote.abs.dto.AbsLibrariesResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse
import com.chapterplayer.data.remote.abs.dto.AbsLoginRequest
import com.chapterplayer.data.remote.abs.dto.AbsMeDto
import com.chapterplayer.data.remote.abs.dto.AbsPatchProgressRequest
import com.chapterplayer.data.remote.abs.dto.AbsProgressDto
import com.chapterplayer.data.remote.abs.dto.AbsSyncSessionRequest
import com.chapterplayer.data.remote.abs.dto.AbsLoginResponseDto
import com.chapterplayer.data.remote.abs.dto.AbsPlayDeviceInfo
import com.chapterplayer.data.remote.abs.dto.AbsPlayRequest
import com.chapterplayer.data.remote.abs.dto.AbsPlaySessionDto
import com.chapterplayer.data.remote.abs.dto.AbsStatusDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/**
 * The Audiobookshelf REST API.
 *
 * Auth: Bearer token in the Authorization header. Every call takes the server's base URL,
 * because each configured server has its own address.
 */
class AbsApiService(private val client: HttpClient) {

    /**
     * Fetch all libraries on this ABS server.
     * Used to let the user pick which library to load books from.
     */
    suspend fun getLibraries(baseUrl: String, bearerToken: String): AbsLibrariesResponse =
        client.get(endpoint(baseUrl, "api/libraries")) {
            header(HttpHeaders.Authorization, bearerToken)
        }.body()

    /**
     * Fetch one page of audiobook items in a library.
     * ABS paginates, so this returns up to [limit] items; callers walk the pages with fetchAllAbsItems.
     */
    suspend fun getLibraryItems(
        baseUrl: String,
        bearerToken: String,
        libraryId: String,
        limit: Int = 100,
        page: Int = 0,
        include: String = "progress",
    ): AbsLibraryItemsResponse =
        client.get(endpoint(baseUrl, "api/libraries/${libraryId.encodeURLPathPart()}/items")) {
            header(HttpHeaders.Authorization, bearerToken)
            parameter("limit", limit)
            parameter("page", page)
            parameter("include", include)
        }.body()

    /** One page of the collections in a library (GET /api/libraries/{id}/collections). */
    suspend fun getLibraryCollections(
        baseUrl: String,
        bearerToken: String,
        libraryId: String,
        limit: Int = 100,
        page: Int = 0,
    ): AbsCollectionsResponse =
        client.get(endpoint(baseUrl, "api/libraries/${libraryId.encodeURLPathPart()}/collections")) {
            header(HttpHeaders.Authorization, bearerToken)
            parameter("limit", limit)
            parameter("page", page)
        }.body()

    /** One item with its chapters and full metadata (GET /api/items/{id}?expanded=1). [itemId] is the server's own id. */
    suspend fun getItem(baseUrl: String, bearerToken: String, itemId: String): AbsItemDetailDto =
        client.get(endpoint(baseUrl, "api/items/${itemId.encodeURLPathPart()}")) {
            header(HttpHeaders.Authorization, bearerToken)
            parameter("expanded", 1)
        }.body()

    /** Opens a playback session for one item (POST /api/items/{id}/play). [itemId] is the server's own id. */
    suspend fun startPlayback(baseUrl: String, bearerToken: String, itemId: String): AbsPlaySessionDto =
        client.post(endpoint(baseUrl, "api/items/${itemId.encodeURLPathPart()}/play")) {
            header(HttpHeaders.Authorization, bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AbsPlayRequest(AbsPlayDeviceInfo(clientName = "ChapterPlayer", deviceId = DEVICE_ID)))
        }.body()

    /** Ends a playback session (POST /api/session/{id}/close) with where listening stopped and how long it ran, in seconds. */
    suspend fun closeSession(baseUrl: String, bearerToken: String, sessionId: String, positionSec: Double, listenedSec: Double) {
        client.post(endpoint(baseUrl, "api/session/${sessionId.encodeURLPathPart()}/close")) {
            header(HttpHeaders.Authorization, bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AbsCloseSessionRequest(currentTime = positionSec, timeListened = listenedSec))
        }
    }

    /** Reports an open session (POST /api/session/{id}/sync): where listening is, the time listened since the last sync and the book length, in seconds. */
    suspend fun syncSession(
        baseUrl: String, bearerToken: String, sessionId: String,
        positionSec: Double, listenedSec: Double, durationSec: Double,
    ) {
        client.post(endpoint(baseUrl, "api/session/${sessionId.encodeURLPathPart()}/sync")) {
            header(HttpHeaders.Authorization, bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AbsSyncSessionRequest(currentTime = positionSec, timeListened = listenedSec, duration = durationSec))
        }
    }

    /** The user's progress on one item (GET /api/me/progress/{id}); null when the server has none (404). */
    suspend fun getProgress(baseUrl: String, bearerToken: String, itemId: String): AbsProgressDto? =
        try {
            client.get(endpoint(baseUrl, "api/me/progress/${itemId.encodeURLPathPart()}")) {
                header(HttpHeaders.Authorization, bearerToken)
            }.body()
        } catch (e: HttpStatusException) {
            if (e.status == HttpStatusCode.NotFound) null else throw e
        }

    /** Sets the user's progress on one item (PATCH /api/me/progress/{id}), without a session. Times in seconds. */
    suspend fun patchProgress(
        baseUrl: String, bearerToken: String, itemId: String,
        positionSec: Double, durationSec: Double, isFinished: Boolean,
    ) {
        val fraction = if (durationSec > 0.0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0
        client.patch(endpoint(baseUrl, "api/me/progress/${itemId.encodeURLPathPart()}")) {
            header(HttpHeaders.Authorization, bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AbsPatchProgressRequest(currentTime = positionSec, duration = durationSec, progress = fraction, isFinished = isFinished))
        }
    }

    /** The user's progress on every item (GET /api/me, the `mediaProgress` list only). */
    suspend fun getMeProgress(baseUrl: String, bearerToken: String): List<AbsProgressDto> =
        client.get(endpoint(baseUrl, "api/me")) {
            header(HttpHeaders.Authorization, bearerToken)
        }.body<AbsMeDto>().mediaProgress

    /** The user's bookmarks on every item (GET /api/me, the `bookmarks` list only). */
    suspend fun getMeBookmarks(baseUrl: String, bearerToken: String): List<AbsBookmarkDto> =
        client.get(endpoint(baseUrl, "api/me")) {
            header(HttpHeaders.Authorization, bearerToken)
        }.body<AbsMeBookmarksDto>().bookmarks

    /** Creates a bookmark (POST /api/me/item/{id}/bookmark) at a whole second. [itemId] is the server's own id. */
    suspend fun createBookmark(baseUrl: String, bearerToken: String, itemId: String, timeSec: Long, title: String) {
        client.post(endpoint(baseUrl, "api/me/item/${itemId.encodeURLPathPart()}/bookmark")) {
            header(HttpHeaders.Authorization, bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AbsBookmarkRequest(timeSec, title))
        }
    }

    /** Renames the bookmark at a whole second (PATCH /api/me/item/{id}/bookmark, the verb Audiobookshelf documents). */
    suspend fun renameBookmark(baseUrl: String, bearerToken: String, itemId: String, timeSec: Long, title: String) {
        client.patch(endpoint(baseUrl, "api/me/item/${itemId.encodeURLPathPart()}/bookmark")) {
            header(HttpHeaders.Authorization, bearerToken)
            contentType(ContentType.Application.Json)
            setBody(AbsBookmarkRequest(timeSec, title))
        }
    }

    /** Deletes the bookmark at a whole second (DELETE /api/me/item/{id}/bookmark/{time}); a 404 means it is already gone. */
    suspend fun deleteBookmark(baseUrl: String, bearerToken: String, itemId: String, timeSec: Long) {
        try {
            client.delete(endpoint(baseUrl, "api/me/item/${itemId.encodeURLPathPart()}/bookmark/$timeSec")) {
                header(HttpHeaders.Authorization, bearerToken)
            }
        } catch (e: HttpStatusException) {
            if (e.status != HttpStatusCode.NotFound) throw e
        }
    }

    /** Answers without a token: the server version and which sign-in methods are on. */
    suspend fun getStatus(baseUrl: String): AbsStatusDto =
        client.get(endpoint(baseUrl, "status")).body()

    /** Signs in. The header makes the server put the refresh token in the response body. */
    suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto =
        client.post(endpoint(baseUrl, "login")) {
            contentType(ContentType.Application.Json)
            header("x-return-tokens", "true")
            setBody(AbsLoginRequest(username, password))
        }.body()

    /** Trades a refresh token for a new access token and a new refresh token. */
    suspend fun refresh(baseUrl: String, refreshToken: String): AbsLoginResponseDto =
        client.post(endpoint(baseUrl, "auth/refresh")) {
            header("x-refresh-token", refreshToken)
        }.body()

    companion object {
        /** The app sends no per-install id at sign-in, so one constant names the Android client. */
        private const val DEVICE_ID = "chapterplayer-android"

        /**
         * Where one audio file of an item downloads from (GET /api/items/{id}/file/{ino}/download).
         * The token is never part of it: it goes in the Authorization header of each request.
         */
        fun fileDownloadUrl(baseUrl: String, itemId: String, ino: String): String =
            baseUrl.trimEnd('/') + "/api/items/${itemId.encodeURLPathPart()}/file/${ino.encodeURLPathPart()}/download"
    }

    private fun endpoint(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path
}
