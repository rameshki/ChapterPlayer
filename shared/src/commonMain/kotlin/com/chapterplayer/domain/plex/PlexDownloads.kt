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
 * `<ratingKey>.<ext>` (the extension from the part's container, [plexFileExtension]) and keeps its size (0 when Plex
 * gave none, to be asked for before downloading), its length (0 when unknown) and its media type. A track repeating a
 * rating key already planned is dropped. A track without a file makes the whole book [PlexDownloadPlan.Unavailable]
 * ([PLEX_AUDIO_MISSING_TEXT]): it could not be placed on the book's timeline.
 */
fun plexDownloadPlan(tracks: List<PlexTrack>): PlexDownloadPlan {
    if (tracks.any { it.partKey.isNullOrBlank() }) return PlexDownloadPlan.Unavailable(PLEX_AUDIO_MISSING_TEXT)
    val seen = HashSet<String>()
    val files = tracks
        .filter { it.ratingKey.isNotBlank() && seen.add(it.ratingKey) }
        .mapIndexed { order, track ->
            PlexDownloadFile(
                PlannedFile(
                    ino = track.ratingKey,
                    fileName = safeFileName("${track.ratingKey}.${plexFileExtension(track.container)}"),
                    totalBytes = track.sizeBytes?.takeIf { it > 0L } ?: 0L,
                    sortOrder = order,
                    durationMs = track.durationMs.coerceAtLeast(0L),
                    mimeType = plexMimeType(track.container),
                ),
                track.partKey!!,
            )
        }
    return PlexDownloadPlan.Files(files)
}

private val EXTENSION = Regex("[a-z0-9]{1,8}")

/** The file extension for a Plex part's [container] ("mp3", "mp4", "m4b"), or "audio" when there is no usable one. */
fun plexFileExtension(container: String?): String {
    val ext = container?.trim()?.lowercase().orEmpty()
    return if (EXTENSION.matches(ext)) ext else "audio"
}

private val CONTENT_RANGE_TOTAL = Regex("bytes\\s+\\d+-\\d+/(\\d+)")

/**
 * A file's size from the answer to a request for its first byte (`Range: bytes=0-0`): the total of a 206's
 * `Content-Range`, or the `Content-Length` of a 200 (a server that ignored the range). Null otherwise.
 */
fun plexSizeFromAnswer(status: Int, contentRange: String?, contentLength: String?): Long? = when (status) {
    206 -> contentRange?.trim()?.let { CONTENT_RANGE_TOTAL.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }
    200 -> contentLength?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    else -> null
}
