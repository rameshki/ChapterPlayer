package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsLibrariesResponse
import com.chapterplayer.data.remote.abs.dto.AbsLibraryItemsResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Audiobookshelf REST API.
 *
 * Auth: Bearer token in the Authorization header.
 * Base URL is set dynamically per-server in NetworkModule.
 */
interface AbsApiService {

    /**
     * Fetch all libraries on this ABS server.
     * Used to let the user pick which library to load books from.
     */
    @GET("api/libraries")
    suspend fun getLibraries(
        @Header("Authorization") bearerToken: String
    ): AbsLibrariesResponse

    /**
     * Fetch all audiobook items in a library.
     * ABS paginates, so this returns one page of up to [limit] items; callers walk the pages with fetchAllAbsItems.
     */
    @GET("api/libraries/{libraryId}/items")
    suspend fun getLibraryItems(
        @Header("Authorization") bearerToken: String,
        @Path("libraryId") libraryId: String,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 0,
        @Query("include") include: String = "progress"
    ): AbsLibraryItemsResponse
}
