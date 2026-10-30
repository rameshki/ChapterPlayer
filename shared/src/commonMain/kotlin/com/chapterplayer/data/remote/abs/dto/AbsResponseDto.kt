package com.chapterplayer.data.remote.abs.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level response from GET /api/libraries/{id}/items
 */
@Serializable
data class AbsLibraryItemsResponse(
    @SerialName("results") val results: List<AbsLibraryItemDto>,
    // Audiobookshelf omits "total" on some pages, so it is optional.
    @SerialName("total") val total: Int? = null,
    @SerialName("limit") val limit: Int,
    @SerialName("page") val page: Int
)

@Serializable
data class AbsLibraryItemDto(
    @SerialName("id") val id: String,
    @SerialName("media") val media: AbsMediaDto,
    @SerialName("addedAt") val addedAt: Long? = null          // epoch milliseconds
)

@Serializable
data class AbsMediaDto(
    @SerialName("metadata") val metadata: AbsMetadataDto,
    @SerialName("chapters") val chapters: List<AbsChapterDto>? = null,
    @SerialName("duration") val duration: Double? = null,           // seconds
    @SerialName("coverPath") val coverPath: String? = null
)

@Serializable
data class AbsMetadataDto(
    @SerialName("title") val title: String? = null,
    @SerialName("authorName") val authorName: String? = null,
    @SerialName("narratorName") val narratorName: String? = null,
    @SerialName("seriesName") val seriesName: String? = null   // "Name #3"
)

@Serializable
data class AbsChapterDto(
    @SerialName("id") val id: Int,
    @SerialName("start") val start: Double,   // seconds
    @SerialName("end") val end: Double,       // seconds
    @SerialName("title") val title: String
)

/**
 * Response from GET /api/libraries — used when user needs to pick a library.
 */
@Serializable
data class AbsLibrariesResponse(
    @SerialName("libraries") val libraries: List<AbsLibraryDto>
)

@Serializable
data class AbsLibraryDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("mediaType") val mediaType: String   // "book" or "podcast"
)
