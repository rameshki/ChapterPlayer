package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.PLEX_PLATFORM_NAME
import com.chapterplayer.data.remote.plex.dto.PlexLibraryAllResponse
import com.chapterplayer.data.remote.plex.dto.PlexLibrarySectionsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
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
 * The playback reports (timeline, scrobble) also say which app is playing ([clientIdentifier]).
 */
class PlexApiService(
    private val client: HttpClient,
    private val clientIdentifier: PlexClientIdentifier,
) {

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

    /**
     * One page of a section's tracks (type 10), each with its album's rating key (parentRatingKey) and length:
     * summed per album, they give the books' lengths that the album list leaves out.
     */
    suspend fun getSectionTracks(
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
            parameter("type", PLEX_TYPE_TRACK)
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

    /**
     * Tells the server where a track is being played: `GET /:/timeline` with the track's rating key and metadata key,
     * [state] (`playing`, `paused` or `stopped`), the offset [timeMs] and the track's length [durationMs]. This is how
     * Plex clients report playback; the server keeps the offset as the track's `viewOffset`.
     */
    suspend fun timeline(
        baseUrl: String,
        token: String,
        ratingKey: String,
        state: String,
        timeMs: Long,
        durationMs: Long,
    ) {
        client.get(endpoint(baseUrl, ":/timeline")) {
            reportHeaders(token)
            parameter("ratingKey", ratingKey)
            parameter("key", "/library/metadata/$ratingKey")
            parameter("state", state)
            parameter("time", timeMs.coerceAtLeast(0L))
            if (durationMs > 0L) parameter("duration", durationMs)
            parameter("identifier", LIBRARY_IDENTIFIER)
        }
    }

    /** Marks a track as played to the end (`GET /:/scrobble`): its play count goes up and its offset is cleared. */
    suspend fun scrobble(baseUrl: String, token: String, ratingKey: String) {
        client.get(endpoint(baseUrl, ":/scrobble")) {
            reportHeaders(token)
            parameter("key", ratingKey)
            parameter("identifier", LIBRARY_IDENTIFIER)
        }
    }

    /** Marks a track as not played (`GET /:/unscrobble`): its play count and offset are cleared. */
    suspend fun unscrobble(baseUrl: String, token: String, ratingKey: String) {
        client.get(endpoint(baseUrl, ":/unscrobble")) {
            reportHeaders(token)
            parameter("key", ratingKey)
            parameter("identifier", LIBRARY_IDENTIFIER)
        }
    }

    // The token, JSON, and who is playing: Plex attributes a timeline to the client that sends it.
    private fun HttpRequestBuilder.reportHeaders(token: String) {
        header("X-Plex-Token", token)
        header(HttpHeaders.Accept, "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Platform", PLEX_PLATFORM_NAME)
        header("X-Plex-Client-Identifier", clientIdentifier.value)
    }

    private fun endpoint(baseUrl: String, path: String) = baseUrl.trimEnd('/') + "/" + path

    private companion object {
        /** Plex's metadata type number for an album. */
        const val PLEX_TYPE_ALBUM = 9
        /** Plex's metadata type number for a track. */
        const val PLEX_TYPE_TRACK = 10
        const val CONTAINER_START = "X-Plex-Container-Start"
        const val CONTAINER_SIZE = "X-Plex-Container-Size"
        /** The library plug-in a scrobble or timeline is for. */
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
        const val PRODUCT = "ChapterPlayer"
    }
}
