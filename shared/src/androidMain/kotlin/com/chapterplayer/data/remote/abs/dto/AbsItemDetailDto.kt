package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.SeriesRef
import com.chapterplayer.domain.downloads.AudioFileEntry
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
    @SerialName("chapters") val chapters: List<AbsItemDetailChapterDto> = emptyList(),
    @SerialName("audioFiles") val audioFiles: List<AbsAudioFileDto> = emptyList()
)

/** One audio file of the item; [ino] is the server's file id, used to download it. */
@Serializable
data class AbsAudioFileDto(
    @SerialName("index") val index: Int? = null,
    @SerialName("ino") val ino: String? = null,
    @SerialName("metadata") val metadata: AbsAudioFileMetadataDto? = null,
    @SerialName("exclude") val exclude: Boolean = false
)

@Serializable
data class AbsAudioFileMetadataDto(
    @SerialName("filename") val filename: String? = null,
    @SerialName("size") val size: Long? = null
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
    @SerialName("title") val title: String = ""
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
            ?.replace("&nbsp;", " ")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    val chapters = media.chapters.map { it.toChapter() }

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


/** A chapter in milliseconds; shared by the details and the play session. */
internal fun AbsItemDetailChapterDto.toChapter() = Chapter(
    id = id,
    title = title,
    startMs = (start * 1000).roundToLong(),
    endMs = (end * 1000).roundToLong()
)

/** The audio files as listed, for the download plan (`planFiles` sorts and filters them). */
fun AbsItemDetailDto.toAudioFileEntries(): List<AudioFileEntry> = media.audioFiles.map {
    AudioFileEntry(
        ino = it.ino,
        fileName = it.metadata?.filename,
        sizeBytes = it.metadata?.size,
        index = it.index,
        excluded = it.exclude
    )
}
