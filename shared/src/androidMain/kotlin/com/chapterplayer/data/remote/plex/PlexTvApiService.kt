package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexPinDto
import com.chapterplayer.data.remote.plex.dto.PlexResourceDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import javax.inject.Inject

/**
 * The plex.tv cloud API at https://plex.tv/.
 * This is separate from PlexApiService, which talks to a Plex Media Server.
 *
 * Auth flow:
 *   1. POST /api/v2/pins gives a pin code
 *   2. Open the browser at app.plex.tv/auth with the code
 *   3. Poll GET /api/v2/pins/{id} until authToken is non-null
 *   4. Use authToken with GET /api/v2/resources to discover the user's servers
 */
class PlexTvApiService @Inject constructor(private val client: HttpClient) {

    /**
     * Create a new auth pin. Returns a code for the sign-in page, and a pin ID we use to
     * poll for completion.
     */
    suspend fun createPin(
        product: String,
        version: String,
        clientId: String,
        accept: String = "application/json",
        strong: Boolean = true,
    ): PlexPinDto =
        client.post("$BASE/api/v2/pins") {
            plexHeaders(product, version, clientId, accept)
            parameter("strong", strong)
        }.body()

    /**
     * Poll this endpoint after the browser is opened.
     * authToken is null until the user completes sign-in on plex.tv.
     */
    suspend fun checkPin(
        pinId: Long,
        product: String,
        version: String,
        clientId: String,
        accept: String = "application/json",
    ): PlexPinDto =
        client.get("$BASE/api/v2/pins/$pinId") {
            plexHeaders(product, version, clientId, accept)
        }.body()

    /**
     * Fetch all resources (servers, players, etc.) on the signed-in account.
     * Filter by provides == "server" to get Plex Media Servers.
     */
    suspend fun getResources(
        token: String,
        product: String,
        version: String,
        clientId: String,
        accept: String = "application/json",
        includeHttps: Int = 1,
        includeRelay: Int = 1,
    ): List<PlexResourceDto> =
        client.get("$BASE/api/v2/resources") {
            header("X-Plex-Token", token)
            plexHeaders(product, version, clientId, accept)
            parameter("includeHttps", includeHttps)
            parameter("includeRelay", includeRelay)
        }.body()

    private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders(
        product: String,
        version: String,
        clientId: String,
        accept: String,
    ) {
        header("X-Plex-Product", product)
        header("X-Plex-Version", version)
        header("X-Plex-Client-Identifier", clientId)
        header(HttpHeaders.Accept, accept)
    }

    private companion object {
        const val BASE = "https://plex.tv"
    }
}
