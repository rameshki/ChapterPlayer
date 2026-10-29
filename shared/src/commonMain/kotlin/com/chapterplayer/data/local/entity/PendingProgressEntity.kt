package com.chapterplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chapterplayer.domain.playback.PendingProgress

/** The latest position of a book that the server has not heard yet. One row per book. */
@Entity(tableName = "pending_progress")
data class PendingProgressEntity(
    @PrimaryKey val bookId: String,
    val serverId: String,
    val positionMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val updatedAtMs: Long,
    val attempts: Int,
    @ColumnInfo(defaultValue = "0") val nextAttemptAtMs: Long = 0L,
    /** The finished state a "Mark as finished / not finished" asked for; null for an ordinary position. */
    @ColumnInfo(defaultValue = "NULL") val markedFinished: Boolean? = null,
    /** A mirror (merged sources): the copy whose listening this row repeats; null for the book's own positions and marks. */
    @ColumnInfo(defaultValue = "NULL") val mirrorOf: String? = null
) {
    fun toDomain(): PendingProgress = PendingProgress(
        bookId = bookId,
        serverId = serverId,
        positionMs = positionMs,
        durationMs = durationMs,
        listenedMs = listenedMs,
        updatedAtMs = updatedAtMs,
        attempts = attempts,
        nextAttemptAtMs = nextAttemptAtMs,
        markedFinished = markedFinished,
        mirrorOf = mirrorOf
    )

    companion object {
        fun fromDomain(pending: PendingProgress, nextAttemptAtMs: Long = 0L): PendingProgressEntity = PendingProgressEntity(
            bookId = pending.bookId,
            serverId = pending.serverId,
            positionMs = pending.positionMs,
            durationMs = pending.durationMs,
            listenedMs = pending.listenedMs,
            updatedAtMs = pending.updatedAtMs,
            attempts = pending.attempts,
            nextAttemptAtMs = nextAttemptAtMs,
            markedFinished = pending.markedFinished,
            mirrorOf = pending.mirrorOf
        )
    }
}
