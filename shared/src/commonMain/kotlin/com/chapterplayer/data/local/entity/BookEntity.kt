package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
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
    val cachedAt: Long            // Unix timestamp ms — used for cache invalidation
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
        serverId = serverId
    )
}
