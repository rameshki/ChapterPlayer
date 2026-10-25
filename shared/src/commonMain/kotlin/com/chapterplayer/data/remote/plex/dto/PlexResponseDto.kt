package com.chapterplayer.data.remote.plex.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plex API responses are wrapped in a MediaContainer.
 * We request JSON via Accept: application/json header.
 */
@Serializable
data class PlexLibrarySectionsResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexSectionsContainer
)

@Serializable
data class PlexSectionsContainer(
    @SerialName("size") val size: Int,
    @SerialName("Directory") val directories: List<PlexSectionDto>? = null
)

@Serializable
data class PlexSectionDto(
    @SerialName("key") val key: String,
    @SerialName("title") val title: String,
    @SerialName("type") val type: String   // "artist", "movie", "show" — audiobooks are "artist"
)

/**
 * Any list of metadata: a section listing (`/library/sections/{key}/all`), an item's children
 * (`/library/metadata/{key}/children`) or one item (`/library/metadata/{key}`).
 */
@Serializable
data class PlexLibraryAllResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexBooksContainer
)

@Serializable
data class PlexBooksContainer(
    @SerialName("size") val size: Int = 0,
    /** The whole list's length when the server pages it; null when it sent everything. */
    @SerialName("totalSize") val totalSize: Int? = null,
    @SerialName("offset") val offset: Int? = null,
    @SerialName("Metadata") val metadata: List<PlexMetadataDto>? = null
)

@Serializable
data class PlexMetadataDto(
    @SerialName("ratingKey") val ratingKey: String,
    @SerialName("title") val title: String = "",
    /** "artist", "album" or "track" in a music section. */
    @SerialName("type") val type: String? = null,
    /** For a track its album's rating key (a section's track list says which book each track belongs to). */
    @SerialName("parentRatingKey") val parentRatingKey: String? = null,
    /** For an album the artist (the book's author); for a track the album. */
    @SerialName("parentTitle") val author: String? = null,
    @SerialName("summary") val summary: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("index") val index: Int? = null,
    @SerialName("duration") val durationMs: Long? = null,        // Plex provides duration in ms
    @SerialName("thumb") val thumb: String? = null,              // Relative path; prepend server URL
    @SerialName("addedAt") val addedAt: Long? = null,            // Plex gives epoch seconds
    /** Where listening stopped inside this item, in ms; absent when it was never started or was finished. */
    @SerialName("viewOffset") val viewOffsetMs: Long? = null,
    /** How often this item was played to the end (scrobbled); absent means never. */
    @SerialName("viewCount") val viewCount: Int? = null,
    /** When this item was last played, in epoch seconds. */
    @SerialName("lastViewedAt") val lastViewedAt: Long? = null,
    @SerialName("Genre") val genres: List<PlexTagDto>? = null,
    @SerialName("Media") val media: List<PlexMediaDto>? = null,
    @SerialName("Chapter") val chapters: List<PlexChapterDto>? = null
)

@Serializable
data class PlexTagDto(
    @SerialName("tag") val tag: String = ""
)

@Serializable
data class PlexMediaDto(
    @SerialName("duration") val durationMs: Long? = null,
    @SerialName("container") val container: String? = null,
    @SerialName("Part") val parts: List<PlexPartDto>? = null
)

@Serializable
data class PlexPartDto(
    @SerialName("id") val id: Long,
    @SerialName("key") val key: String,   // Stream path, e.g. /library/parts/12345/file.m4b
    @SerialName("duration") val durationMs: Long? = null,
    @SerialName("container") val container: String? = null,
    @SerialName("size") val sizeBytes: Long? = null
)

@Serializable
data class PlexChapterDto(
    @SerialName("id") val id: Int = 0,
    @SerialName("filter") val filter: String? = null,
    @SerialName("index") val index: Int = 0,
    @SerialName("tag") val title: String = "",
    @SerialName("startTimeOffset") val startTimeMs: Long = 0L,  // Plex provides this in ms
    @SerialName("endTimeOffset") val endTimeMs: Long? = null
)
