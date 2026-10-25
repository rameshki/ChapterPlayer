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

/**
 * Offline only: a download that does not know its files' lengths yet cannot be placed on a timeline. The lengths are
 * filled in from the server the next time the phone is online (`DownloadLengthFiller`
 * on Android) or when the book is played online.
 */
const val NEEDS_ONE_ONLINE_PLAY_TEXT = "This download needs a connection once before it plays offline"

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

private val FILE_INO = Regex("/file/([^/?#]+)")

/** The server's file id in a session track's address (`.../api/items/{id}/file/{ino}`); null when it has none. */
fun trackIno(url: String): String? = FILE_INO.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

/** The session's tracks by the file id in their address; tracks without one are left out. */
private fun tracksByIno(session: PlaybackSession?): Map<String, PlaybackTrack> =
    session?.tracks.orEmpty().sortedBy { it.index }
        .mapNotNull { t -> trackIno(t.url)?.let { it to t } }
        .distinctBy { it.first }
        .toMap()

/**
 * A downloaded book played from [files] (in book order). The session's tracks are matched to the files by file id.
 * When the [session] lists exactly these files in this order, its timeline is used as it is (the same as streaming);
 * otherwise each file's length is the session's for that file, else the stored one, else (for a single file) the
 * book's, and the files follow one another. The session's length and chapters win over the book's. Each track's
 * address is [toUri] of the file's path. Null when there are no files or a length is unknown.
 */
fun playableFromPhone(
    book: AudioBook,
    files: List<LocalFile>,
    session: PlaybackSession?,
    toUri: (String) -> String,
): PlayableBook? {
    if (files.isEmpty()) return null
    val byIno = tracksByIno(session)
    val sameFiles = byIno.size == files.size && session?.tracks?.size == files.size &&
        byIno.keys.toList() == files.map { it.ino }
    val tracks = if (sameFiles) {
        files.mapIndexed { i, file ->
            val t = byIno.getValue(file.ino)
            PlaybackTrack(i, t.startMs, t.durationMs, file.mimeType.ifEmpty { t.mimeType }, toUri(file.path))
        }
    } else {
        var start = 0L
        files.mapIndexed { i, file ->
            val fromSession = byIno[file.ino]
            val length = when {
                fromSession != null && fromSession.durationMs > 0L -> fromSession.durationMs
                file.durationMs > 0L -> file.durationMs
                files.size == 1 && book.durationMs > 0L -> book.durationMs
                else -> return null
            }
            val mime = file.mimeType.ifEmpty { fromSession?.mimeType.orEmpty() }
            PlaybackTrack(i, start, length, mime, toUri(file.path)).also { start += length }
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

/** Whether [book]'s audio is downloaded files (so removing the download takes it away from the player). */
fun playsFromPhone(book: PlayableBook): Boolean = book.tracks.any { it.url.startsWith("file:") }

/** The session's file lengths (by file id) for the [files] that have none stored, matched by file id. */
fun durationsToRemember(files: List<LocalFile>, session: PlaybackSession?): Map<String, Long> {
    val byIno = tracksByIno(session)
    return files.mapNotNull { file ->
        val track = byIno[file.ino]
        if (file.durationMs <= 0L && track != null && track.durationMs > 0L) file.ino to track.durationMs else null
    }.toMap()
}
