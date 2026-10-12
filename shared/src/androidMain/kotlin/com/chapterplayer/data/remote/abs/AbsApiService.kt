package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsLibrariesResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import javax.inject.Inject

/**
 * The Audiobookshelf REST API.
 *
 * Auth: Bearer token in the Authorization header. Every call takes the server's base URL,
 * because each configured server has its own address.
 */
class AbsApiService @Inject constructor(private val client: HttpClient) {

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

    private fun endpoint(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path
}
