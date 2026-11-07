package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of "Up next", the person's queue of books to play after the current one, kept on this phone only (Room v15).
 * A row names one copy's id; a book several servers have is one entry and is queued once (see `resolveUpNext`).
 */
@Entity(tableName = "up_next")
data class UpNextEntity(
    @PrimaryKey val bookId: String,
    /** The place in the queue, 0 first. */
    val position: Int,
    /** When it was queued, in epoch milliseconds. */
    val addedAt: Long,
)
