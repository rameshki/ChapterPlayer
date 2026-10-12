package com.chapterplayer.data.remote.abs.dto

import com.squareup.moshi.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.squareup.moshi.JsonClass

/**
 * Top-level response from GET /api/libraries/{id}/items
 */
@JsonClass(generateAdapter = true)
@Serializable
data class AbsLibraryItemsResponse(
    @Json(name = "results") @SerialName("results") val results: List<AbsLibraryItemDto>,
    // Audiobookshelf omits "total" on some pages, so it is optional.
    @Json(name = "total") @SerialName("total") val total: Int? = null,
    @Json(name = "limit") @SerialName("limit") val limit: Int,
    @Json(name = "page") @SerialName("page") val page: Int
)

@JsonClass(generateAdapter = true)
@Serializable
data class AbsLibraryItemDto(
    @Json(name = "id") @SerialName("id") val id: String,
    @Json(name = "media") @SerialName("media") val media: AbsMediaDto
)

@JsonClass(generateAdapter = true)
@Serializable
data class AbsMediaDto(
    @Json(name = "metadata") @SerialName("metadata") val metadata: AbsMetadataDto,
    @Json(name = "chapters") @SerialName("chapters") val chapters: List<AbsChapterDto>? = null,
    @Json(name = "duration") @SerialName("duration") val duration: Double? = null,           // seconds
    @Json(name = "coverPath") @SerialName("coverPath") val coverPath: String? = null
)

@JsonClass(generateAdapter = true)
@Serializable
data class AbsMetadataDto(
    @Json(name = "title") @SerialName("title") val title: String? = null,
    @Json(name = "authorName") @SerialName("authorName") val authorName: String? = null,
    @Json(name = "narratorName") @SerialName("narratorName") val narratorName: String? = null
)

@JsonClass(generateAdapter = true)
@Serializable
data class AbsChapterDto(
    @Json(name = "id") @SerialName("id") val id: Int,
    @Json(name = "start") @SerialName("start") val start: Double,   // seconds
    @Json(name = "end") @SerialName("end") val end: Double,       // seconds
    @Json(name = "title") @SerialName("title") val title: String
)

/**
 * Response from GET /api/libraries — used when user needs to pick a library.
 */
@JsonClass(generateAdapter = true)
@Serializable
data class AbsLibrariesResponse(
    @Json(name = "libraries") @SerialName("libraries") val libraries: List<AbsLibraryDto>
)

@JsonClass(generateAdapter = true)
@Serializable
data class AbsLibraryDto(
    @Json(name = "id") @SerialName("id") val id: String,
    @Json(name = "name") @SerialName("name") val name: String,
    @Json(name = "mediaType") @SerialName("mediaType") val mediaType: String   // "book" or "podcast"
)
