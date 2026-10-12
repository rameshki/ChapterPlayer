package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexLibrarySectionsResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Plex Media Server REST API.
 *
 * Auth: X-Plex-Token query parameter or header.
 * We always request JSON via Accept header (set in the OkHttp interceptor).
 */
interface PlexApiService {

    /**
     * Fetch all library sections on this Plex server.
     * Used to let the user pick which section contains their audiobooks.
     */
    @GET("library/sections")
    suspend fun getLibrarySections(
        @Header("X-Plex-Token") token: String,
        @Header("Accept") accept: String = "application/json"
    ): PlexLibrarySectionsResponse

    /**
     * Fetch all items in a library section.
     * For audiobooks, the section type is typically "artist" in Plex.
     * type=9 filters for audiobooks specifically when using the Plex music library type.
     */
    @GET("library/sections/{sectionId}/all")
    suspend fun getLibraryAll(
        @Header("X-Plex-Token") token: String,
        @Path("sectionId") sectionId: String,
        @Header("Accept") accept: String = "application/json",
        @Query("includeChapters") includeChapters: Int = 1
    ): PlexLibraryAllResponse
}
