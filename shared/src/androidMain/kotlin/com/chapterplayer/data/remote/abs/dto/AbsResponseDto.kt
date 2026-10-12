package com.chapterplayer.data.remote.abs.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Top-level response from GET /api/libraries/{id}/items
 */
@JsonClass(generateAdapter = true)
data class AbsLibraryItemsResponse(
    @Json(name = "results") val results: List<AbsLibraryItemDto>,
    // Audiobookshelf omits "total" on some pages, so it is optional.
    @Json(name = "total") val total: Int?,
    @Json(name = "limit") val limit: Int,
    @Json(name = "page") val page: Int
)

@JsonClass(generateAdapter = true)
data class AbsLibraryItemDto(
    @Json(name = "id") val id: String,
    @Json(name = "media") val media: AbsMediaDto
)

@JsonClass(generateAdapter = true)
data class AbsMediaDto(
    @Json(name = "metadata") val metadata: AbsMetadataDto,
    @Json(name = "chapters") val chapters: List<AbsChapterDto>?,
    @Json(name = "duration") val duration: Double?,           // seconds
    @Json(name = "coverPath") val coverPath: String?
)

@JsonClass(generateAdapter = true)
data class AbsMetadataDto(
    @Json(name = "title") val title: String?,
    @Json(name = "authorName") val authorName: String?,
    @Json(name = "narratorName") val narratorName: String?
)

@JsonClass(generateAdapter = true)
data class AbsChapterDto(
    @Json(name = "id") val id: Int,
    @Json(name = "start") val start: Double,   // seconds
    @Json(name = "end") val end: Double,       // seconds
    @Json(name = "title") val title: String
)

/**
 * Response from GET /api/libraries — used when user needs to pick a library.
 */
@JsonClass(generateAdapter = true)
data class AbsLibrariesResponse(
    @Json(name = "libraries") val libraries: List<AbsLibraryDto>
)

@JsonClass(generateAdapter = true)
data class AbsLibraryDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "mediaType") val mediaType: String   // "book" or "podcast"
)
