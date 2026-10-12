package com.chapterplayer.data.remote.plex.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Plex API responses are wrapped in a MediaContainer.
 * We request JSON via Accept: application/json header.
 */
@JsonClass(generateAdapter = true)
data class PlexLibrarySectionsResponse(
    @Json(name = "MediaContainer") val mediaContainer: PlexSectionsContainer
)

@JsonClass(generateAdapter = true)
data class PlexSectionsContainer(
    @Json(name = "size") val size: Int,
    @Json(name = "Directory") val directories: List<PlexSectionDto>?
)

@JsonClass(generateAdapter = true)
data class PlexSectionDto(
    @Json(name = "key") val key: String,
    @Json(name = "title") val title: String,
    @Json(name = "type") val type: String   // "artist", "movie", "show" — audiobooks are "artist"
)

/**
 * Response from GET /library/sections/{sectionId}/all
 */
@JsonClass(generateAdapter = true)
data class PlexLibraryAllResponse(
    @Json(name = "MediaContainer") val mediaContainer: PlexBooksContainer
)

@JsonClass(generateAdapter = true)
data class PlexBooksContainer(
    @Json(name = "size") val size: Int,
    @Json(name = "Metadata") val metadata: List<PlexMetadataDto>?
)

@JsonClass(generateAdapter = true)
data class PlexMetadataDto(
    @Json(name = "ratingKey") val ratingKey: String,
    @Json(name = "title") val title: String,
    @Json(name = "parentTitle") val author: String?,       // Author stored as parentTitle in Plex
    @Json(name = "summary") val summary: String?,
    @Json(name = "duration") val durationMs: Long?,        // Plex provides duration in ms
    @Json(name = "thumb") val thumb: String?,              // Relative path — prepend server URL
    @Json(name = "Media") val media: List<PlexMediaDto>?,
    @Json(name = "Chapter") val chapters: List<PlexChapterDto>?
)

@JsonClass(generateAdapter = true)
data class PlexMediaDto(
    @Json(name = "Part") val parts: List<PlexPartDto>?
)

@JsonClass(generateAdapter = true)
data class PlexPartDto(
    @Json(name = "id") val id: Long,
    @Json(name = "key") val key: String   // Stream path, e.g. /library/parts/12345/file.m4b
)

@JsonClass(generateAdapter = true)
data class PlexChapterDto(
    @Json(name = "id") val id: Int,
    @Json(name = "filter") val filter: String?,
    @Json(name = "index") val index: Int,
    @Json(name = "tag") val title: String,
    @Json(name = "startTimeOffset") val startTimeMs: Long  // Plex provides this in ms
)
