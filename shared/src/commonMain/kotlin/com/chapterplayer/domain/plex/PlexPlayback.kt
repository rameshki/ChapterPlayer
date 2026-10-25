package com.chapterplayer.domain.plex

import com.chapterplayer.data.playback.PlaybackTrack
import com.chapterplayer.domain.playback.ServerProgress

/** Shown when a Plex book has a track without an audio file: it cannot be put on one timeline. */
const val PLEX_AUDIO_MISSING_TEXT = "The server has no audio file for part of this book"

/** The media type for a Plex part's [container]; empty when unknown, so the player works it out from the file. */
fun plexMimeType(container: String?): String = when (container?.trim()?.lowercase()) {
    "mp4", "m4a", "m4b", "aac" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "ogg", "opus" -> "audio/ogg"
    else -> ""
}

/**
 * The player's items for a Plex book: one per track, in play order, on one timeline (each starts where the tracks
 * before it end). The address is the part's path under the server's [baseUrl] and carries no token: the data source
 * adds the `X-Plex-Token` header to each request. Null when a track has no file (see [PLEX_AUDIO_MISSING_TEXT]).
 */
fun plexPlaybackTracks(baseUrl: String, tracks: List<PlexTrack>): List<PlaybackTrack>? {
    if (tracks.any { it.partKey == null }) return null
    var start = 0L
    return tracks.mapIndexed { index, track ->
        PlaybackTrack(
            index = index,
            startMs = start,
            durationMs = track.durationMs,
            mimeType = plexMimeType(track.container),
            url = plexUrl(baseUrl, track.partKey!!),
        ).also { start += track.durationMs }
    }
}

/**
 * The book's progress as the Plex server holds it, from each track's listening state. Plex keeps progress per track:
 * a track played to the end is scrobbled (`viewCount` > 0, no offset) and a track in progress has a `viewOffset`.
 *
 * - A track in progress (an offset; of several the one with the newest `lastViewedAt`, then the later one): its start on
 *   the book's timeline (the finished tracks before it, in order) plus its offset.
 * - No track in progress and every track finished: the book is finished.
 * - No track in progress and some finished: the start of the first track not finished yet.
 * - Nothing heard: null.
 *
 * Plex has no update time for a book, so [ServerProgress.updatedAtMs] is the newest `lastViewedAt` of the tracks (0 when
 * none has one).
 */
fun plexServerProgress(tracks: List<PlexTrack>): ServerProgress? {
    if (tracks.isEmpty()) return null
    val duration = tracks.sumOf { it.durationMs }
    val updatedAtMs = (tracks.mapNotNull { it.lastViewedAtSec }.maxOrNull() ?: 0L) * 1000L
    val starts = tracks.runningFold(0L) { at, track -> at + track.durationMs }
    val inProgress = tracks.indices
        .filter { (tracks[it].viewOffsetMs ?: 0L) > 0L }
        .maxWithOrNull(compareBy<Int> { tracks[it].lastViewedAtSec ?: 0L }.thenBy { it })
    return when {
        inProgress != null -> {
            val track = tracks[inProgress]
            val offset = track.viewOffsetMs!!.let { if (track.durationMs > 0L) it.coerceAtMost(track.durationMs) else it }
            ServerProgress(starts[inProgress] + offset, duration, isFinished = false, updatedAtMs = updatedAtMs)
        }
        tracks.all { it.viewCount > 0 } -> ServerProgress(duration, duration, isFinished = true, updatedAtMs = updatedAtMs)
        tracks.any { it.viewCount > 0 } -> {
            val next = tracks.indexOfFirst { it.viewCount == 0 }
            ServerProgress(starts[next], duration, isFinished = false, updatedAtMs = updatedAtMs)
        }
        else -> null
    }
}
