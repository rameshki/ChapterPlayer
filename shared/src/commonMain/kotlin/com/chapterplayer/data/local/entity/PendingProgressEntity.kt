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
    @ColumnInfo(defaultValue = "0") val nextAttemptAtMs: Long = 0L
) {
    fun toDomain(): PendingProgress = PendingProgress(
        bookId = bookId,
        serverId = serverId,
        positionMs = positionMs,
        durationMs = durationMs,
        listenedMs = listenedMs,
        updatedAtMs = updatedAtMs,
        attempts = attempts
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
            nextAttemptAtMs = nextAttemptAtMs
        )
    }
}
