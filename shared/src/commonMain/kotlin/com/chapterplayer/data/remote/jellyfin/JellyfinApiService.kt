package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.remote.jellyfin.dto.JellyfinAuthResultDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinAuthenticateByNameDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemsPageDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinPublicInfoDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinUserDataDto
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinAuthHeader
import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.JELLYFIN_DEVICE_NAME
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess

/**
 * The Jellyfin REST API (the calls of J1). Every call takes the server address as typed ([base], which may carry a
 * path such as `/jellyfin`; a trailing slash is dropped) and, after sign-in, the row's DeviceId and token. The token
 * travels only in the `Authorization: MediaBrowser ...` header, never in a URL. A non-2xx answer throws
 * `HttpStatusException` (the client's `installApiDefaults`).
 *
 * [clientInfo] builds what the app tells the server about itself for a DeviceId (device name, app version). Koin always
 * passes the real one (with the app version); the default (no version) exists so Koin's `verify()`, which cannot
 * resolve a function-typed parameter, skips it, as it does for the sources' `markSignedOut`.
 */
class JellyfinApiService(
    private val client: HttpClient,
    private val clientInfo: (deviceId: String) -> JellyfinClient = { JellyfinClient(JELLYFIN_DEVICE_NAME, it, "") },
) {

    /**
     * `GET /System/Info/Public`, with no auth header at all (setup and Test connection use the probe client). The status
     * is checked and the text decoded here, because the probe client has no JSON converter and leaves statuses to the
     * caller: a non-2xx (a redirect too) throws `HttpStatusException`, a body that is not this JSON (a web page) throws
     * a serialization error.
     */
    suspend fun getPublicInfo(base: String): JellyfinPublicInfoDto {
        val response = client.get(endpoint(base, "System/Info/Public")) {
            header(HttpHeaders.Accept, JSON)
        }
        if (!response.status.isSuccess()) throw HttpStatusException(response.status)
        return ApiJson.decodeFromString(JellyfinPublicInfoDto.serializer(), response.bodyAsText())
    }

    /** `POST /Users/AuthenticateByName` with the header but no token yet. */
    suspend fun authenticateByName(base: String, deviceId: String, username: String, password: String): JellyfinAuthResultDto =
        client.post(endpoint(base, "Users/AuthenticateByName")) {
            authorize(deviceId, token = null)
            contentType(ContentType.Application.Json)
            setBody(JellyfinAuthenticateByNameDto(username, password))
        }.body()

    /** `GET /UserViews`: the user's libraries (the books ones are kept by the caller). */
    suspend fun getUserViews(base: String, deviceId: String, token: String): JellyfinItemsPageDto =
        client.get(endpoint(base, "UserViews")) {
            authorize(deviceId, token)
        }.body()

    /** One page of [JELLYFIN_PAGE_SIZE] `AudioBook` items of the view [viewId], from [startIndex]. */
    suspend fun getAudiobooksPage(base: String, deviceId: String, token: String, viewId: String, startIndex: Int): JellyfinItemsPageDto =
        client.get(endpoint(base, "Items")) {
            authorize(deviceId, token)
            parameter("ParentId", viewId)
            parameter("IncludeItemTypes", "AudioBook")
            parameter("Recursive", "true")
            parameter("Fields", LIST_FIELDS)
            parameter("EnableUserData", "true")
            parameter("EnableImageTypes", "Primary")
            parameter("StartIndex", startIndex)
            parameter("Limit", JELLYFIN_PAGE_SIZE)
            parameter("EnableTotalRecordCount", "true")
        }.body()

    /** `GET /Items/{Id}` with chapters, people and media sources (details and playback). */
    suspend fun getItem(base: String, deviceId: String, token: String, itemId: String): JellyfinItemDto =
        client.get(endpoint(base, "Items/${itemId.encodeURLPathPart()}")) {
            authorize(deviceId, token)
            parameter("Fields", DETAIL_FIELDS)
        }.body()

    /** `GET /UserItems/{Id}/UserData`: the signed-in user's play state of one item. */
    suspend fun getUserData(base: String, deviceId: String, token: String, itemId: String): JellyfinUserDataDto =
        client.get(endpoint(base, "UserItems/${itemId.encodeURLPathPart()}/UserData")) {
            authorize(deviceId, token)
        }.body()

    private fun HttpRequestBuilder.authorize(deviceId: String, token: String?) {
        header(HttpHeaders.Authorization, jellyfinAuthHeader(clientInfo(deviceId), token))
        header(HttpHeaders.Accept, JSON)
    }

    private fun endpoint(base: String, path: String) = base.trimEnd('/') + "/" + path

    private companion object {
        const val JSON = "application/json"
        const val LIST_FIELDS = "People,Overview,Genres,DateCreated,ProviderIds"
        const val DETAIL_FIELDS = "Chapters,People,Overview,Genres,Studios,MediaSources"
    }
}
