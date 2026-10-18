package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.SeriesRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

private val HTML_TAG = Regex("<[^>]*>")

/**
 * Represents the detailed information for an item in the ABS system.
 */
@Serializable
data class AbsItemDetailDto(
    @SerialName("id") val id: String,
    @SerialName("media") val media: AbsItemDetailMediaDto
)

/**
 * Contains media-specific content such as metadata and chapters.
 */
@Serializable
data class AbsItemDetailMediaDto(
    @SerialName("metadata") val metadata: AbsItemDetailMetadataDto = AbsItemDetailMetadataDto(),
    @SerialName("chapters") val chapters: List<AbsItemDetailChapterDto> = emptyList()
)

/**
 * Holds descriptive metadata for the media item.
 */
@Serializable
data class AbsItemDetailMetadataDto(
    @SerialName("description") val description: String? = null,
    @SerialName("descriptionPlain") val descriptionPlain: String? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("publishedYear") val publishedYear: String? = null,
    @SerialName("series") val series: List<AbsItemDetailSeriesDto> = emptyList()
)

/**
 * Represents an individual chapter within the media.
 */
@Serializable
data class AbsItemDetailChapterDto(
    @SerialName("id") val id: Int,
    @SerialName("start") val start: Double,
    @SerialName("end") val end: Double,
    @SerialName("title") val title: String
)

/**
 * Represents a series reference associated with the media.
 */
@Serializable
data class AbsItemDetailSeriesDto(
    @SerialName("name") val name: String,
    @SerialName("sequence") val sequence: String? = null
)

/**
 * Maps the DTO to the domain-level BookDetails model.
 */
fun AbsItemDetailDto.toBookDetails(): BookDetails {
    val metadata = media.metadata
    
    val description = metadata.descriptionPlain?.trim()?.takeIf { it.isNotEmpty() }
        ?: metadata.description
            ?.replace(HTML_TAG, "")
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    val chapters = media.chapters.map {
        Chapter(
            id = it.id,
            title = it.title,
            startMs = (it.start * 1000).roundToLong(),
            endMs = (it.end * 1000).roundToLong()
        )
    }

    val series = metadata.series
        .filter { it.name.isNotBlank() }
        .map { SeriesRef(name = it.name.trim(), sequence = it.sequence) }

    return BookDetails(
        chapters = chapters,
        description = description,
        genres = metadata.genres,
        publishedYear = metadata.publishedYear,
        series = series
    )
}
