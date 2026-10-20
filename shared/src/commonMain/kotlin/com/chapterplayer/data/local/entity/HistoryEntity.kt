package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chapterplayer.domain.playback.HistoryEntry

/** A position the listener left in a book (local only). */
@Entity(tableName = "position_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val positionMs: Long,
    val recordedAt: Long
) {
    fun toDomain(): HistoryEntry = HistoryEntry(bookId, positionMs, recordedAt)

    companion object {
        fun fromDomain(entry: HistoryEntry): HistoryEntity = HistoryEntity(0, entry.bookId, entry.positionMs, entry.recordedAt)
    }
}
