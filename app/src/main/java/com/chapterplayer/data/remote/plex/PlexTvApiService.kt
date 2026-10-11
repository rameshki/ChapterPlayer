package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexPinDto
import com.chapterplayer.data.remote.plex.dto.PlexResourceDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the plex.tv cloud API.
 * This is separate from PlexApiService, which talks to a local Plex Media Server.
 *
 * Base URL: https://plex.tv/
 *
 * Auth flow:
 *   1. POST /api/v2/pins → get a pin code
 *   2. Open browser to app.plex.tv/auth with the code
 *   3. Poll GET /api/v2/pins/{id} until authToken is non-null
 *   4. Use authToken with GET /api/v2/resources to discover the user's servers
 */
interface PlexTvApiService {

    /**
     * Create a new auth pin. Returns a short code the user will see during sign-in,
     * and a pin ID we use to poll for completion.
     */
    @POST("api/v2/pins")
    suspend fun createPin(
        @Header("X-Plex-Product") product: String,
        @Header("X-Plex-Version") version: String,
        @Header("X-Plex-Client-Identifier") clientId: String,
        @Header("Accept") accept: String = "application/json",
        @Query("strong") strong: Boolean = true
    ): PlexPinDto

    /**
     * Poll this endpoint after the browser is opened.
     * authToken will be null until the user completes sign-in on plex.tv.
     */
    @GET("api/v2/pins/{pinId}")
    suspend fun checkPin(
        @Path("pinId") pinId: Long,
        @Header("X-Plex-Product") product: String,
        @Header("X-Plex-Version") version: String,
        @Header("X-Plex-Client-Identifier") clientId: String,
        @Header("Accept") accept: String = "application/json"
    ): PlexPinDto

    /**
     * Fetch all resources (servers, players, etc.) associated with the signed-in account.
     * Filter by provides == "server" to get Plex Media Servers.
     */
    @GET("api/v2/resources")
    suspend fun getResources(
        @Header("X-Plex-Token") token: String,
        @Header("X-Plex-Product") product: String,
        @Header("X-Plex-Version") version: String,
        @Header("X-Plex-Client-Identifier") clientId: String,
        @Header("Accept") accept: String = "application/json",
        @Query("includeHttps") includeHttps: Int = 1,
        @Query("includeRelay") includeRelay: Int = 1
    ): List<PlexResourceDto>
}
