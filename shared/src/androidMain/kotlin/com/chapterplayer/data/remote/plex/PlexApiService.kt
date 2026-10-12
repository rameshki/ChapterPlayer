package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexLibrarySectionsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart

/**
 * The Plex Media Server REST API.
 *
 * Auth: X-Plex-Token header. JSON is requested with an Accept header on every call.
 * Every call takes the server's base URL, because each configured server has its own address.
 */
class PlexApiService(private val client: HttpClient) {

    /**
     * Fetch all library sections on this Plex server.
     * Used to let the user pick which section contains their audiobooks.
     */
    suspend fun getLibrarySections(
        baseUrl: String,
        token: String,
        accept: String = "application/json",
    ): PlexLibrarySectionsResponse =
        client.get(endpoint(baseUrl, "library/sections")) {
            header("X-Plex-Token", token)
            header(HttpHeaders.Accept, accept)
        }.body()

    /**
     * Fetch all items in a library section.
     * For audiobooks, the section type is typically "artist" in Plex.
     */
    suspend fun getLibraryAll(
        baseUrl: String,
        token: String,
        sectionId: String,
        accept: String = "application/json",
        includeChapters: Int = 1,
    ): PlexLibraryAllResponse =
        client.get(endpoint(baseUrl, "library/sections/${sectionId.encodeURLPathPart()}/all")) {
            header("X-Plex-Token", token)
            header(HttpHeaders.Accept, accept)
            parameter("includeChapters", includeChapters)
        }.body()

    private fun endpoint(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path
}
