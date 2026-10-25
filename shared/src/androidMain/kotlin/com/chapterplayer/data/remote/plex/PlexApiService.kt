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
     * One page of a section's albums (`type=9`): in a Plex music library each audiobook is an album, with the
     * author as the album's artist. The top level of a music section lists artists, which is why the type is asked for.
     * Paging goes in both the query and the headers, because Plex reads either.
     */
    suspend fun getSectionAlbums(
        baseUrl: String,
        token: String,
        sectionId: String,
        start: Int,
        size: Int,
        accept: String = "application/json",
    ): PlexLibraryAllResponse =
        client.get(endpoint(baseUrl, "library/sections/${sectionId.encodeURLPathPart()}/all")) {
            header("X-Plex-Token", token)
            header(HttpHeaders.Accept, accept)
            header(CONTAINER_START, start)
            header(CONTAINER_SIZE, size)
            parameter("type", PLEX_TYPE_ALBUM)
            parameter(CONTAINER_START, start)
            parameter(CONTAINER_SIZE, size)
        }.body()

    /** The children of one item: an artist's albums or an album's tracks, in the server's order. */
    suspend fun getChildren(
        baseUrl: String,
        token: String,
        ratingKey: String,
        accept: String = "application/json",
    ): PlexLibraryAllResponse =
        client.get(endpoint(baseUrl, "library/metadata/${ratingKey.encodeURLPathPart()}/children")) {
            header("X-Plex-Token", token)
            header(HttpHeaders.Accept, accept)
        }.body()

    /** One item's full metadata; with [includeChapters] a track carries its chapters (a track list never does). */
    suspend fun getMetadata(
        baseUrl: String,
        token: String,
        ratingKey: String,
        includeChapters: Boolean = false,
        accept: String = "application/json",
    ): PlexLibraryAllResponse =
        client.get(endpoint(baseUrl, "library/metadata/${ratingKey.encodeURLPathPart()}")) {
            header("X-Plex-Token", token)
            header(HttpHeaders.Accept, accept)
            if (includeChapters) parameter("includeChapters", 1)
        }.body()

    private fun endpoint(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path

    private companion object {
        /** Plex's metadata type number for an album. */
        const val PLEX_TYPE_ALBUM = 9
        const val CONTAINER_START = "X-Plex-Container-Start"
        const val CONTAINER_SIZE = "X-Plex-Container-Size"
    }
}
