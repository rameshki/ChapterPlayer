package com.chapterplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.ServerSource

/**
 * Room entity for caching audiobook metadata.
 * Chapters are stored as a JSON string and deserialized on read.
 * We intentionally keep the schema flat — chapters are rarely queried independently.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val durationMs: Long,
    val chaptersJson: String,     // JSON-encoded List<Chapter>
    val streamUrl: String,
    val coverUrl: String?,
    val source: String,           // ServerSource.name()
    val serverId: String,
    val cachedAt: Long,           // Unix timestamp ms — used for cache invalidation
    @ColumnInfo(defaultValue = "''") val libraryId: String = "",
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0L,   // epoch ms; 0 when unknown
    val seriesName: String? = null,
    val seriesSequence: String? = null
) {
    fun toDomain(chapters: List<Chapter>): AudioBook = AudioBook(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        durationMs = durationMs,
        chapters = chapters,
        streamUrl = streamUrl,
        coverUrl = coverUrl,
        source = ServerSource.valueOf(source),
        serverId = serverId,
        libraryId = libraryId,
        addedAt = addedAt,
        seriesName = seriesName,
        seriesSequence = seriesSequence
    )

    companion object {
        /** The row stored for [book], stamped with when it was fetched. */
        fun fromDomain(book: AudioBook, cachedAt: Long): BookEntity = BookEntity(
            id = book.id,
            title = book.title,
            author = book.author,
            narrator = book.narrator,
            durationMs = book.durationMs,
            chaptersJson = ChapterJson.encode(book.chapters),
            streamUrl = book.streamUrl,
            coverUrl = book.coverUrl,
            source = book.source.name,
            serverId = book.serverId,
            cachedAt = cachedAt,
            libraryId = book.libraryId,
            addedAt = book.addedAt,
            seriesName = book.seriesName,
            seriesSequence = book.seriesSequence
        )
    }
}
