package com.chapterplayer.data.local.entity

import androidx.room.Entity
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState

/** One bookmark: a book and a whole second identify it. [state] is the [BookmarkState] name. */
@Entity(tableName = "bookmarks", primaryKeys = ["bookId", "timeSec"])
data class BookmarkEntity(
    val bookId: String,
    val timeSec: Long,
    val serverId: String,
    val title: String,
    val createdAt: Long,
    val state: String
) {
    fun toDomain(): Bookmark = Bookmark(
        bookId = bookId,
        timeSec = timeSec,
        title = title,
        createdAt = createdAt,
        // An unknown name cannot happen; treat it as in sync so nothing is pushed by mistake.
        state = BookmarkState.entries.firstOrNull { it.name == state } ?: BookmarkState.SYNCED
    )

    companion object {
        fun fromDomain(bookmark: Bookmark, serverId: String): BookmarkEntity = BookmarkEntity(
            bookId = bookmark.bookId,
            timeSec = bookmark.timeSec,
            serverId = serverId,
            title = bookmark.title,
            createdAt = bookmark.createdAt,
            state = bookmark.state.name
        )
    }
}
