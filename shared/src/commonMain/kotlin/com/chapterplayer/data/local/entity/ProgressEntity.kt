package com.chapterplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chapterplayer.data.model.ReadingProgress

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val isFinished: Boolean,
    /** Copied from another copy of the same book on another server (merged sources), not listened to on this one. */
    @ColumnInfo(defaultValue = "0") val mirrored: Boolean = false
) {
    fun toDomain(): ReadingProgress = ReadingProgress(
        bookId = bookId,
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedAt = lastPlayedAt,
        isFinished = isFinished,
        mirrored = mirrored
    )

    companion object {
        fun fromDomain(progress: ReadingProgress): ProgressEntity = ProgressEntity(
            bookId = progress.bookId,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            lastPlayedAt = progress.lastPlayedAt,
            isFinished = progress.isFinished,
            mirrored = progress.mirrored
        )
    }
}
