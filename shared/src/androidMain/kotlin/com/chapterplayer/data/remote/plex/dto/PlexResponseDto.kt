package com.chapterplayer.data.remote.plex.dto

import com.squareup.moshi.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.squareup.moshi.JsonClass

/**
 * Plex API responses are wrapped in a MediaContainer.
 * We request JSON via Accept: application/json header.
 */
@JsonClass(generateAdapter = true)
@Serializable
data class PlexLibrarySectionsResponse(
    @Json(name = "MediaContainer") @SerialName("MediaContainer") val mediaContainer: PlexSectionsContainer
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexSectionsContainer(
    @Json(name = "size") @SerialName("size") val size: Int,
    @Json(name = "Directory") @SerialName("Directory") val directories: List<PlexSectionDto>? = null
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexSectionDto(
    @Json(name = "key") @SerialName("key") val key: String,
    @Json(name = "title") @SerialName("title") val title: String,
    @Json(name = "type") @SerialName("type") val type: String   // "artist", "movie", "show" — audiobooks are "artist"
)

/**
 * Response from GET /library/sections/{sectionId}/all
 */
@JsonClass(generateAdapter = true)
@Serializable
data class PlexLibraryAllResponse(
    @Json(name = "MediaContainer") @SerialName("MediaContainer") val mediaContainer: PlexBooksContainer
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexBooksContainer(
    @Json(name = "size") @SerialName("size") val size: Int,
    @Json(name = "Metadata") @SerialName("Metadata") val metadata: List<PlexMetadataDto>? = null
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexMetadataDto(
    @Json(name = "ratingKey") @SerialName("ratingKey") val ratingKey: String,
    @Json(name = "title") @SerialName("title") val title: String,
    @Json(name = "parentTitle") @SerialName("parentTitle") val author: String? = null,       // Author stored as parentTitle in Plex
    @Json(name = "summary") @SerialName("summary") val summary: String? = null,
    @Json(name = "duration") @SerialName("duration") val durationMs: Long? = null,        // Plex provides duration in ms
    @Json(name = "thumb") @SerialName("thumb") val thumb: String? = null,              // Relative path — prepend server URL
    @Json(name = "Media") @SerialName("Media") val media: List<PlexMediaDto>? = null,
    @Json(name = "Chapter") @SerialName("Chapter") val chapters: List<PlexChapterDto>? = null
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexMediaDto(
    @Json(name = "Part") @SerialName("Part") val parts: List<PlexPartDto>? = null
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexPartDto(
    @Json(name = "id") @SerialName("id") val id: Long,
    @Json(name = "key") @SerialName("key") val key: String   // Stream path, e.g. /library/parts/12345/file.m4b
)

@JsonClass(generateAdapter = true)
@Serializable
data class PlexChapterDto(
    @Json(name = "id") @SerialName("id") val id: Int,
    @Json(name = "filter") @SerialName("filter") val filter: String? = null,
    @Json(name = "index") @SerialName("index") val index: Int,
    @Json(name = "tag") @SerialName("tag") val title: String,
    @Json(name = "startTimeOffset") @SerialName("startTimeOffset") val startTimeMs: Long  // Plex provides this in ms
)
