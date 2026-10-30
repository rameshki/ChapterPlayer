package com.chapterplayer.domain.plex

import com.chapterplayer.data.playback.PlaybackTrack
import com.chapterplayer.domain.playback.MAX_PLAYBACK_SPEED
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.playback.TrackSpan
import com.chapterplayer.domain.playback.locate

/** Shown when a Plex book has a track without an audio file: it cannot be put on one timeline. */
const val PLEX_AUDIO_MISSING_TEXT = "The server has no audio file for part of this book"

/** Shown when the server does not know a track's length: the track cannot be placed on the book's timeline. */
const val PLEX_LENGTH_UNKNOWN_TEXT = "The server does not know the length of part of this book"

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
 * adds the `X-Plex-Token` header to each request.
 *
 * @throws IllegalStateException with [PLEX_AUDIO_MISSING_TEXT] when a track has no file, or [PLEX_LENGTH_UNKNOWN_TEXT]
 * when a track's length is unknown (positions after it could not be placed). Both texts are safe to show.
 */
fun plexPlaybackTracks(baseUrl: String, tracks: List<PlexTrack>): List<PlaybackTrack> {
    check(tracks.none { it.partKey == null }) { PLEX_AUDIO_MISSING_TEXT }
    check(tracks.none { it.durationMs <= 0L }) { PLEX_LENGTH_UNKNOWN_TEXT }
    var start = 0L
    return tracks.mapIndexed { index, track ->
        PlaybackTrack(
            index = index,
            startMs = start,
            durationMs = track.durationMs,
            mimeType = plexMimeType(track.container),
            url = plexUrl(baseUrl, track.partKey!!),
            fileId = track.ratingKey,
        ).also { start += track.durationMs }
    }
}

/**
 * The book's progress as the Plex server holds it, from each track's listening state. Plex keeps progress per track:
 * a track played to the end is scrobbled (`viewCount` > 0, no offset) and a track in progress has a `viewOffset`.
 *
 * - Every track finished and at most one offset left (a timeline that reached a finished track after its scrobble): the
 *   book is finished.
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
    val offsets = tracks.count { (it.viewOffsetMs ?: 0L) > 0L }
    return when {
        tracks.all { it.viewCount > 0 } && offsets <= 1 ->
            ServerProgress(duration, duration, isFinished = true, updatedAtMs = updatedAtMs)
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

/** Where a book position is for Plex: the track ([trackIndex] in play order, [ratingKey]), the offset in it and its length. */
data class PlexTimelinePoint(val trackIndex: Int, val ratingKey: String, val offsetMs: Long, val durationMs: Long)

/** A track counts as played to the end when listening is within this much of its end. */
const val PLEX_TRACK_DONE_WITHIN_MS = 5_000L

/** The track that holds [bookPositionMs] and the offset inside it (clamped to the book); null without tracks. */
fun plexTimelinePoint(tracks: List<PlexTrack>, bookPositionMs: Long): PlexTimelinePoint? {
    val spans = tracks.runningFold(0L) { at, track -> at + track.durationMs }
        .zip(tracks) { start, track -> TrackSpan(0, start, track.durationMs) }
    val at = locate(spans, bookPositionMs.coerceAtLeast(0L)) ?: return null
    val track = tracks[at.trackIndex]
    return PlexTimelinePoint(at.trackIndex, track.ratingKey, at.offsetMs, track.durationMs)
}

private fun nearEnd(point: PlexTimelinePoint): Boolean =
    point.durationMs > 0L && point.durationMs - point.offsetMs <= PLEX_TRACK_DONE_WITHIN_MS

/**
 * The tracks (indexes in play order) that a report at book position [positionMs] finishes, after the session's last
 * report was at [previousPositionMs] (null for its first report) and [listenedMs] of wall-clock listening since.
 *
 * A track counts as played through when its end lies between the two positions and the move forward is no longer than
 * playing could have covered ([listenedMs] at the fastest speed, plus [PLEX_TRACK_DONE_WITHIN_MS]); a longer jump is a
 * seek, and the tracks it went over (or the rest of the one it left) were not heard. The track of [positionMs] also
 * counts when listening is within [PLEX_TRACK_DONE_WITHIN_MS] of its end. The first report finishes nothing before it.
 */
fun plexCompletedTracks(tracks: List<PlexTrack>, previousPositionMs: Long?, positionMs: Long, listenedMs: Long): List<Int> {
    val point = plexTimelinePoint(tracks, positionMs) ?: return emptyList()
    val ends = tracks.runningFold(0L) { at, track -> at + track.durationMs }.drop(1)
    val playable = (listenedMs.coerceAtLeast(0L) * MAX_PLAYBACK_SPEED).toLong() + PLEX_TRACK_DONE_WITHIN_MS
    val passed = if (previousPositionMs != null && positionMs > previousPositionMs && positionMs - previousPositionMs <= playable) {
        tracks.indices.filter { ends[it] > previousPositionMs && ends[it] <= positionMs }
    } else {
        emptyList()
    }
    val own = if (nearEnd(point)) listOf(point.trackIndex) else emptyList()
    return (passed + own).distinct()
}

/**
 * What to tell Plex to set a book to a position without a playing session (a queued position, Mark as finished / not
 * finished). Plex holds progress per track, so: [unscrobble] the tracks from the position's track on that count as
 * played (first, as that also clears their offsets), set the offset in the position's track ([point], sent as a stopped
 * timeline), then [scrobble] the tracks before it that were not played yet (and its own track when the position is in
 * its last seconds). A finished book scrobbles every track and sends nothing else. Rating keys, in play order.
 */
data class PlexProgressPlan(val scrobble: List<String>, val unscrobble: List<String>, val point: PlexTimelinePoint?)

/** See [PlexProgressPlan]. */
fun plexProgressPlan(tracks: List<PlexTrack>, positionMs: Long, isFinished: Boolean): PlexProgressPlan {
    if (isFinished) return PlexProgressPlan(scrobble = tracks.map { it.ratingKey }, unscrobble = emptyList(), point = null)
    val point = plexTimelinePoint(tracks, positionMs) ?: return PlexProgressPlan(emptyList(), emptyList(), null)
    val before = tracks.take(point.trackIndex).filter { it.viewCount == 0 }.map { it.ratingKey }
    val own = if (nearEnd(point)) listOf(point.ratingKey) else emptyList()
    val after = tracks.drop(point.trackIndex).filter { it.viewCount > 0 }.map { it.ratingKey }
    return PlexProgressPlan(scrobble = before + own, unscrobble = after, point = point)
}
