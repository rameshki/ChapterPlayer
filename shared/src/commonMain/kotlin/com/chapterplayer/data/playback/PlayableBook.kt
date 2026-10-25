package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.LocalFile
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter

/**
 * What the player is given for one book: its audio files in order on the book's timeline, its length and
 * chapters, where the server says listening resumes, and the server [session] when one is open (none when a
 * downloaded book plays offline). Nothing in it is secret.
 */
data class PlayableBook(
    val bookId: String,
    val serverId: String,
    val durationMs: Long,
    /** Where the session says listening resumes; 0 without a session. */
    val startPositionMs: Long,
    val tracks: List<PlaybackTrack>,
    val chapters: List<Chapter>,
    val session: PlaybackSession?,
)

/** A download that does not know its files' lengths cannot be placed on a timeline without the server. */
const val NEEDS_ONE_ONLINE_PLAY_TEXT = "Play this book once while online, then it plays offline too"

/** A book streamed from the server: everything comes from its session. */
fun streamed(session: PlaybackSession): PlayableBook = PlayableBook(
    bookId = session.bookId,
    serverId = session.serverId,
    durationMs = session.durationMs,
    startPositionMs = session.startPositionMs,
    tracks = session.tracks,
    chapters = session.chapters,
    session = session,
)

/**
 * A downloaded book played from [files] (in book order). With a [session] that lists as many files, its timeline,
 * length and chapters are used, exactly as when streaming; otherwise each file's stored length (or, for a single
 * file, the book's), with the book's chapters. Each track's address is [toUri] of the file's path. Null when there
 * are no files or a length is unknown, so the book cannot be placed on a timeline.
 */
fun playableFromPhone(
    book: AudioBook,
    files: List<LocalFile>,
    session: PlaybackSession?,
    toUri: (String) -> String,
): PlayableBook? {
    if (files.isEmpty()) return null
    val sessionTracks = session?.tracks?.sortedBy { it.index }?.takeIf { it.size == files.size }
    val tracks = if (sessionTracks != null) {
        files.mapIndexed { i, file ->
            val t = sessionTracks[i]
            PlaybackTrack(i, t.startMs, t.durationMs, file.mimeType.ifEmpty { t.mimeType }, toUri(file.path))
        }
    } else {
        var start = 0L
        files.mapIndexed { i, file ->
            val length = when {
                file.durationMs > 0L -> file.durationMs
                files.size == 1 && book.durationMs > 0L -> book.durationMs
                else -> return null
            }
            PlaybackTrack(i, start, length, file.mimeType, toUri(file.path)).also { start += length }
        }
    }
    return PlayableBook(
        bookId = book.id,
        serverId = book.serverId,
        durationMs = session?.durationMs?.takeIf { it > 0L } ?: book.durationMs.takeIf { it > 0L } ?: tracks.sumOf { it.durationMs },
        startPositionMs = session?.startPositionMs ?: 0L,
        tracks = tracks,
        chapters = session?.chapters?.takeIf { it.isNotEmpty() } ?: book.chapters,
        session = session,
    )
}

/** The session's file lengths (by file id) for the [files] that have none stored, when the session lists the same files. */
fun durationsToRemember(files: List<LocalFile>, session: PlaybackSession?): Map<String, Long> {
    val tracks = session?.tracks?.sortedBy { it.index }?.takeIf { it.size == files.size } ?: return emptyMap()
    return files.zip(tracks)
        .filter { (file, track) -> file.durationMs <= 0L && track.durationMs > 0L }
        .associate { (file, track) -> file.ino to track.durationMs }
}
