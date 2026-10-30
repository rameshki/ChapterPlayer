package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.domain.playback.ServerProgress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** One `mediaProgress` object (`/api/me`, `/api/me/progress/{id}`); times are in seconds, [lastUpdate] in epoch milliseconds. */
@Serializable
data class AbsProgressDto(
    @SerialName("libraryItemId") val libraryItemId: String = "",
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("progress") val progress: Double = 0.0,
    @SerialName("currentTime") val currentTime: Double = 0.0,
    @SerialName("isFinished") val isFinished: Boolean = false,
    @SerialName("lastUpdate") val lastUpdate: Long = 0,
    /** Set for a podcast episode; only book progress is used. */
    @SerialName("episodeId") val episodeId: String? = null,
) {
    /** The app's terms: milliseconds, rounded. */
    fun toServerProgress(): ServerProgress = ServerProgress(
        positionMs = (currentTime * 1000).roundToLong(),
        durationMs = (duration * 1000).roundToLong(),
        isFinished = isFinished,
        updatedAtMs = lastUpdate
    )
}

/** `GET /api/me`: only the progress list is read (bookmarks belong to a later step). */
@Serializable
data class AbsMeDto(
    @SerialName("mediaProgress") val mediaProgress: List<AbsProgressDto> = emptyList(),
)

/** The body of the session sync: where listening is, how long it ran since the last sync and the book length, in seconds. */
@Serializable
data class AbsSyncSessionRequest(val currentTime: Double, val timeListened: Double, val duration: Double)

/** The body of the progress patch; [progress] is the fraction from 0 to 1. */
@Serializable
data class AbsPatchProgressRequest(val currentTime: Double, val duration: Double, val progress: Double, val isFinished: Boolean)
