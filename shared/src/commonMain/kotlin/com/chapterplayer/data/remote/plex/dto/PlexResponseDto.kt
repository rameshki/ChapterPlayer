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
 * Response from GET /library/sections/{sectionId}/all
 */
@Serializable
data class PlexLibraryAllResponse(
    @SerialName("MediaContainer") val mediaContainer: PlexBooksContainer
)

@Serializable
data class PlexBooksContainer(
    @SerialName("size") val size: Int,
    @SerialName("Metadata") val metadata: List<PlexMetadataDto>? = null
)

@Serializable
data class PlexMetadataDto(
    @SerialName("ratingKey") val ratingKey: String,
    @SerialName("title") val title: String,
    @SerialName("parentTitle") val author: String? = null,       // Author stored as parentTitle in Plex
    @SerialName("summary") val summary: String? = null,
    @SerialName("duration") val durationMs: Long? = null,        // Plex provides duration in ms
    @SerialName("thumb") val thumb: String? = null,              // Relative path — prepend server URL
    @SerialName("addedAt") val addedAt: Long? = null,            // Plex gives epoch seconds
    @SerialName("Media") val media: List<PlexMediaDto>? = null,
    @SerialName("Chapter") val chapters: List<PlexChapterDto>? = null
)

@Serializable
data class PlexMediaDto(
    @SerialName("Part") val parts: List<PlexPartDto>? = null
)

@Serializable
data class PlexPartDto(
    @SerialName("id") val id: Long,
    @SerialName("key") val key: String   // Stream path, e.g. /library/parts/12345/file.m4b
)

@Serializable
data class PlexChapterDto(
    @SerialName("id") val id: Int,
    @SerialName("filter") val filter: String? = null,
    @SerialName("index") val index: Int,
    @SerialName("tag") val title: String,
    @SerialName("startTimeOffset") val startTimeMs: Long  // Plex provides this in ms
)
