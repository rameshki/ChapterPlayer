package com.chapterplayer.domain.jellyfin

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.playback.UNSUPPORTED_AUDIO_TEXT
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemDto
import com.chapterplayer.domain.downloads.PlannedFile
import com.chapterplayer.domain.downloads.safeFileName

/** A book whose file size the server leaves out: the download could not be checked against the space or the end of the file. */
const val JELLYFIN_SIZE_UNKNOWN_TEXT = "The server did not report the size of this book's file"

/** What a Jellyfin book's download is, or why it cannot be downloaded (J3, review R39). */
sealed interface JellyfinDownloadPlan {
    /** One file (a Jellyfin audiobook is one file) and the chapters the server lists. */
    data class Ready(val file: PlannedFile, val chapters: List<Chapter>) : JellyfinDownloadPlan

    /** The book cannot be downloaded; [text] is fixed, safe text for the person. */
    data class Refused(val text: String) : JellyfinDownloadPlan
}

/**
 * The download of [itemId] from its detail [item]: the first media source is the file (the id in `download_files.ino` is the
 * item id, which is also the playback track's file id, so a downloaded book plays from the phone). The same refusals as
 * playback: no media source, a container the app cannot play, an unknown length. And a size the server leaves out, since the
 * download is checked against the free space and against the end of the file (a book that could never be completed is
 * refused now). Chapters come from the item, as for streaming.
 */
fun jellyfinDownloadPlan(itemId: String, item: JellyfinItemDto): JellyfinDownloadPlan {
    val media = item.mediaSources.firstOrNull() ?: return JellyfinDownloadPlan.Refused(JELLYFIN_NO_AUDIO_TEXT)
    val mime = jellyfinMimeType(media.container)
    if (mime.isEmpty()) return JellyfinDownloadPlan.Refused(UNSUPPORTED_AUDIO_TEXT)
    val size = media.size ?: 0L
    if (size <= 0L) return JellyfinDownloadPlan.Refused(JELLYFIN_SIZE_UNKNOWN_TEXT)
    val durationMs = jellyfinTicksToMs(item.runTimeTicks).takeIf { it > 0L } ?: jellyfinTicksToMs(media.runTimeTicks)
    if (durationMs <= 0L) return JellyfinDownloadPlan.Refused(JELLYFIN_LENGTH_UNKNOWN_TEXT)
    val file = PlannedFile(
        ino = itemId,
        fileName = safeFileName("$itemId.${jellyfinFileExtension(media.container)}"),
        totalBytes = size,
        sortOrder = 0,
        durationMs = durationMs,
        mimeType = mime,
    )
    return JellyfinDownloadPlan.Ready(file, jellyfinChapters(item.chapters, durationMs))
}

/**
 * The extension to store a downloaded file under, from the container's media type: Jellyfin names an MP4-family file with
 * ffprobe's comma list (`mov,mp4,m4a,3gp,3g2,mj2`), which is no file name. Only a name for the folder; playback reads the
 * file by its content.
 */
fun jellyfinFileExtension(container: String?): String = when (jellyfinMimeType(container)) {
    "audio/mp4" -> "m4a"
    "audio/aac" -> "aac"
    "audio/mpeg" -> "mp3"
    "audio/flac" -> "flac"
    "audio/ogg" -> "ogg"
    "audio/wav" -> "wav"
    else -> "audio"
}

/** `{base}/Items/{id}/Download`: the original file of the item, with its path kept once. Never a token: it goes in the header. */
fun jellyfinDownloadUrl(base: String, itemId: String): String =
    base.trimEnd('/') + "/Items/" + jellyfinUrlPart(itemId) + "/Download"
