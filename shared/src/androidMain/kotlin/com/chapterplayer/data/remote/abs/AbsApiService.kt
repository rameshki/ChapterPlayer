package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsCollectionsResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibrariesResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse
import com.chapterplayer.data.remote.abs.dto.AbsLoginRequest
import com.chapterplayer.data.remote.abs.dto.AbsLoginResponseDto
import com.chapterplayer.data.remote.abs.dto.AbsStatusDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

    private fun endpoint(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path
}
