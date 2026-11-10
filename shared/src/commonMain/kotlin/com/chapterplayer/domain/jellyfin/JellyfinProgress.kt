package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.remote.jellyfin.dto.JellyfinConfigurationDto

/**
 * The server's audiobook resume thresholds in minutes (`MinAudiobookResume` / `MaxAudiobookResume`, default 5 / 5). A
 * session report under [minResumeMin] is stored as 0; with less than [maxResumeMin] left the book is marked played.
 */
data class AudiobookThresholds(val minResumeMin: Int = 5, val maxResumeMin: Int = 5)

/** The server's thresholds from its configuration; a missing, unreadable or non-positive value is the default 5. */
fun thresholdsOf(dto: JellyfinConfigurationDto?): AudiobookThresholds {
    val defaults = AudiobookThresholds()
    return AudiobookThresholds(
        minResumeMin = dto?.minAudiobookResume?.takeIf { it > 0 } ?: defaults.minResumeMin,
        maxResumeMin = dto?.maxAudiobookResume?.takeIf { it > 0 } ?: defaults.maxResumeMin,
    )
}

/**
 * True where a session report would not keep the real place: in the first [AudiobookThresholds.minResumeMin] minutes
 * (stored as 0) or with less than [AudiobookThresholds.maxResumeMin] minutes left (stored as 0 and played).
 */
fun inThresholdBand(positionMs: Long, durationMs: Long, t: AudiobookThresholds): Boolean =
    positionMs < t.minResumeMin * 60_000L || durationMs - positionMs < t.maxResumeMin * 60_000L

/** What happened in the player that the live session reports. */
enum class JellyfinEvent { START, PLAYING, PAUSED, CLOSED, ENDED }

/** One call to the server, in the order a plan lists them. */
sealed interface JellyfinCall {
    /** `POST /Sessions/Playing`. */
    data class SessionStart(val positionMs: Long) : JellyfinCall

    /** `POST /Sessions/Playing/Progress` (`EventName` `timeupdate`, or `pause` when [paused]). */
    data class SessionProgress(val positionMs: Long, val paused: Boolean) : JellyfinCall

    /** `POST /Sessions/Playing/Stopped`, always with a position (a stop without one marks the book played). */
    data class SessionStopped(val positionMs: Long) : JellyfinCall

    /** `POST /UserItems/{Id}/UserData`: the exact place, no thresholds. */
    data class WriteUserData(val positionMs: Long, val played: Boolean, val lastPlayedAtMs: Long) : JellyfinCall

    /** `POST /UserPlayedItems/{Id}`. */
    data object MarkPlayed : JellyfinCall

    /** `DELETE /UserPlayedItems/{Id}` (kept for the J2 server check; Mark as not finished uses [WriteUserData]). */
    data object MarkUnplayed : JellyfinCall
}

/**
 * The live session's calls for one [event] (spec section 7): the session report keeps the dashboard and other clients
 * current, and where that report would lose the place (a threshold band) the direct UserData write follows it, so the
 * phone's place stays the server's. A pause always writes (the pause place must survive). The real end stops at the end
 * and writes nothing (the server marks the book played). Once the book was set finished while loaded
 * ([markedFinished]), nothing is sent, so a later report cannot undo the mark.
 */
fun jellyfinLivePlan(
    event: JellyfinEvent,
    positionMs: Long,
    durationMs: Long,
    nowMs: Long,
    t: AudiobookThresholds,
    markedFinished: Boolean,
): List<JellyfinCall> {
    if (markedFinished) return emptyList()
    val write = JellyfinCall.WriteUserData(positionMs, played = false, lastPlayedAtMs = nowMs)
    val inBand = inThresholdBand(positionMs, durationMs, t)
    return when (event) {
        JellyfinEvent.START -> listOfNotNull(JellyfinCall.SessionStart(positionMs), write.takeIf { inBand })
        JellyfinEvent.PLAYING -> listOfNotNull(JellyfinCall.SessionProgress(positionMs, paused = false), write.takeIf { inBand })
        JellyfinEvent.PAUSED -> listOf(JellyfinCall.SessionProgress(positionMs, paused = true), write)
        JellyfinEvent.CLOSED -> listOfNotNull(JellyfinCall.SessionStopped(positionMs), write.takeIf { inBand })
        JellyfinEvent.ENDED -> listOf(JellyfinCall.SessionStopped(durationMs))
    }
}

/**
 * A queued position or mark (and a mirror from another copy): no session, no thresholds. Finished marks the book
 * played; anything else writes the exact place at the time it was listened ([playedAtMs]), so a later read of the
 * server is no newer than the phone's own row.
 */
fun jellyfinPushPlan(positionMs: Long, isFinished: Boolean, playedAtMs: Long): List<JellyfinCall> =
    if (isFinished) listOf(JellyfinCall.MarkPlayed)
    else listOf(JellyfinCall.WriteUserData(positionMs, played = false, lastPlayedAtMs = playedAtMs))
