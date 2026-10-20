package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.domain.browse.currentChapterIndex
import com.chapterplayer.domain.playback.TrackSpan
import com.chapterplayer.domain.playback.bookPosition

/** What the player needs to know about one audio file. Nothing in it is secret. */
data class TrackItemSpec(
    /** `"<bookId>/<trackIndex>"`. */
    val mediaId: String,
    val url: String,
    val mimeType: String,
    val title: String,
    val artist: String,
    /** Where this file starts on the book's timeline, so the service can skip by time across files. */
    val startMs: Long,
    val durationMs: Long,
)

/** The player's items for [session], one per audio file, in file order. */
fun trackItemSpecs(book: AudioBook, session: PlaybackSession): List<TrackItemSpec> =
    session.tracks.sortedBy { it.index }.map { track ->
        TrackItemSpec(
            mediaId = "${session.bookId}/${track.index}",
            url = track.url,
            mimeType = track.mimeType,
            title = book.title,
            artist = book.author,
            startMs = track.startMs,
            durationMs = track.durationMs,
        )
    }

/** The files of [session] as timeline spans; the span's list position is the player's item index. */
fun trackSpans(session: PlaybackSession): List<TrackSpan> =
    session.tracks.sortedBy { it.index }.mapIndexed { position, track ->
        TrackSpan(position, track.startMs, track.durationMs)
    }

/**
 * Turns what the player reports (an item and a position inside it) into the book-wide [PlaybackState].
 * With no [tracks] the position is 0.
 */
fun toPlaybackState(
    bookId: String?,
    tracks: List<TrackSpan>,
    itemIndex: Int,
    positionInItemMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    speed: Float,
    chapters: List<Chapter>,
    sleepRemainingMs: Long?,
    error: String?,
): PlaybackState {
    val position = bookPosition(tracks, itemIndex, positionInItemMs)
    return PlaybackState(
        bookId = bookId,
        isPlaying = isPlaying,
        positionMs = position,
        durationMs = durationMs,
        speed = speed,
        chapterIndex = currentChapterIndex(chapters, position),
        isBuffering = isBuffering,
        error = error,
        sleepRemainingMs = sleepRemainingMs,
        chapters = chapters,
    )
}
