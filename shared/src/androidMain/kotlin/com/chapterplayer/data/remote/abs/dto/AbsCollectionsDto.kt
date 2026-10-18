package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.data.model.BookCollection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response type for GET /api/libraries/{id}/collections
 */
@Serializable
data class AbsCollectionsResponse(
    @SerialName("results") val results: List<AbsCollectionDto>,
    @SerialName("total") val total: Int? = null,
    @SerialName("limit") val limit: Int? = null,
    @SerialName("page") val page: Int? = null
)

/**
 * DTO for a collection from the Audiobookshelf API
 */
@Serializable
data class AbsCollectionDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("books") val books: List<AbsCollectionBookDto> = emptyList()
)

/**
 * DTO for a book within a collection from the Audiobookshelf API
 */
@Serializable
data class AbsCollectionBookDto(
    @SerialName("id") val id: String
)

/**
 * Maps this collection DTO to a domain [BookCollection], prefixing book IDs with "abs_"
 */
fun AbsCollectionDto.toBookCollection(serverId: String): BookCollection =
    BookCollection(serverId, id, name, books.map { "abs_" + it.id })
