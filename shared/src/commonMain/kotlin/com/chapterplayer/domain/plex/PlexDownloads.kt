package com.chapterplayer.domain.plex

import com.chapterplayer.domain.downloads.PlannedFile
import com.chapterplayer.domain.downloads.safeFileName

/** Shown when Plex does not tell a file's size, even when asked for its first byte: the download cannot be checked. */
const val PLEX_SIZE_UNKNOWN_TEXT = "Plex did not report the file size"

/** A Plex track to download: the [file] as `download_files` keeps it (its id is the track's rating key) and its part path. */
data class PlexDownloadFile(val file: PlannedFile, val partKey: String)

/** What downloading a Plex book takes. */
sealed interface PlexDownloadPlan {
    /** The files in play order; a file of size 0 has no known size yet. */
    data class Files(val files: List<PlexDownloadFile>) : PlexDownloadPlan

    /** The book cannot be downloaded; [reason] is safe to show. */
    data class Unavailable(val reason: String) : PlexDownloadPlan
}

/**
 * The files of a Plex book, one per track in play order ([tracks] as `plexBookContent` gives them). Each is named
 * `<ratingKey>.<ext>` ([plexFileExtension]: from the part's file name, else its container) and keeps its size (0 when
 * Plex gave none, to be asked for before downloading), its length and its media type. A track repeating a rating key
 * already planned is dropped. The whole book is [PlexDownloadPlan.Unavailable] when a track has no file
 * ([PLEX_AUDIO_MISSING_TEXT]) or no known length ([PLEX_LENGTH_UNKNOWN_TEXT]): either way it could not be placed on the
 * book's timeline, so it could never play from the phone (the same rule as streaming).
 */
fun plexDownloadPlan(tracks: List<PlexTrack>): PlexDownloadPlan {
    if (tracks.any { it.partKey.isNullOrBlank() }) return PlexDownloadPlan.Unavailable(PLEX_AUDIO_MISSING_TEXT)
    if (tracks.any { it.durationMs <= 0L }) return PlexDownloadPlan.Unavailable(PLEX_LENGTH_UNKNOWN_TEXT)
    val seen = HashSet<String>()
    val files = tracks
        .filter { it.ratingKey.isNotBlank() && seen.add(it.ratingKey) }
        .mapIndexed { order, track ->
            val ext = plexFileExtension(track.partKey, track.container)
            PlexDownloadFile(
                PlannedFile(
                    ino = track.ratingKey,
                    fileName = safeFileName("${track.ratingKey}.$ext"),
                    totalBytes = track.sizeBytes?.takeIf { it > 0L } ?: 0L,
                    sortOrder = order,
                    durationMs = track.durationMs,
                    mimeType = plexMimeType(track.container).ifEmpty { plexMimeType(ext) },
                ),
                track.partKey!!,
            )
        }
    return PlexDownloadPlan.Files(files)
}

private val EXTENSION = Regex("[a-z0-9]{1,8}")

/**
 * The file extension for a Plex part: the one in the part path's file name (`/library/parts/1/2/file.m4b` gives "m4b")
 * when it has a usable one, else the part's [container] ("mp3", "mp4"), else "audio".
 */
fun plexFileExtension(partKey: String?, container: String?): String {
    val name = partKey?.substringBefore('?')?.substringAfterLast('/').orEmpty()
    val fromName = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
    if (EXTENSION.matches(fromName)) return fromName
    val ext = container?.trim()?.lowercase().orEmpty()
    return if (EXTENSION.matches(ext)) ext else "audio"
}

private val CONTENT_RANGE_TOTAL = Regex("bytes\\s+\\d+-\\d+/(\\d+)")

/** What the answer to a request for a file's first byte (`Range: bytes=0-0`) says about its size. */
sealed interface PlexSizeAnswer {
    data class Size(val bytes: Long) : PlexSizeAnswer

    /** A definite answer without a size: the download cannot be checked ([PLEX_SIZE_UNKNOWN_TEXT]). */
    data object NoSize : PlexSizeAnswer

    /** The server has no such file (404). */
    data object Missing : PlexSizeAnswer

    /** Nothing definite (a redirect, a server error, a refusal that may pass): ask again later. */
    data object TryLater : PlexSizeAnswer
}

/**
 * Reads the answer to a request for a file's first byte: the total of a 206's `Content-Range`, or the `Content-Length`
 * of a 200 (a server that ignored the range). Only a 2xx without a size is a definite [PlexSizeAnswer.NoSize]; a 404 is
 * [PlexSizeAnswer.Missing]; anything else (3xx, 5xx, 408, 429 and other refusals) is [PlexSizeAnswer.TryLater].
 */
fun plexSizeAnswer(status: Int, contentRange: String?, contentLength: String?): PlexSizeAnswer {
    val size = when (status) {
        206 -> contentRange?.trim()?.let { CONTENT_RANGE_TOTAL.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()
        200 -> contentLength?.trim()?.toLongOrNull()
        else -> null
    }?.takeIf { it > 0L }
    return when {
        size != null -> PlexSizeAnswer.Size(size)
        status in 200..299 -> PlexSizeAnswer.NoSize
        status == 404 -> PlexSizeAnswer.Missing
        else -> PlexSizeAnswer.TryLater
    }
}
