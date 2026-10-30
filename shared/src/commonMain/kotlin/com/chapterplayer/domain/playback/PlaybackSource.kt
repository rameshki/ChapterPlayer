package com.chapterplayer.domain.playback

import com.chapterplayer.domain.downloads.DownloadStatus

/** What this phone holds of a book. */
enum class LocalCopy {
    /** Not downloaded, or the download has not finished. */
    NONE,

    /** Downloaded, and every file is on disk at its full size. */
    COMPLETE,

    /** The download finished but a file is gone or has the wrong size: it plays like a book that is not downloaded. */
    MISSING_FILES,

    /**
     * The download finished but its folder cannot be seen (an SD card taken out or not mounted yet): it cannot play
     * now, but nothing is wrong with the download, so it is left as it is.
     */
    UNREACHABLE,
}

/** One downloaded file: the size the download wrote and what is on disk now (null when the file is gone). */
data class StoredFile(val expectedBytes: Long, val bytesOnDisk: Long?)

/**
 * What the phone holds, from the download's [status], its [files] and whether the book's folder is there
 * ([folderPresent]; false when its volume is not mounted).
 */
fun localCopyOf(status: DownloadStatus?, files: List<StoredFile>, folderPresent: Boolean = true): LocalCopy = when {
    status != DownloadStatus.DONE -> LocalCopy.NONE
    !folderPresent -> LocalCopy.UNREACHABLE
    files.isNotEmpty() && files.all { it.bytesOnDisk != null && it.bytesOnDisk == it.expectedBytes } -> LocalCopy.COMPLETE
    else -> LocalCopy.MISSING_FILES
}

/** A finished download whose files are not all there is marked failed, so the screens offer "Try again". */
fun shouldMarkFailed(copy: LocalCopy): Boolean = copy == LocalCopy.MISSING_FILES

/** Where a book's audio comes from when it starts. */
sealed interface PlaybackSource {
    /** From the server. */
    data object Stream : PlaybackSource

    /** From the downloaded files. Online, a server session is still opened so listening syncs live. */
    data object Local : PlaybackSource

    /** It cannot play now; [reason] is safe to show. */
    data class Unavailable(val reason: String) : PlaybackSource
}

const val NEEDS_CONNECTION_TO_PLAY_TEXT = "Needs a connection to play"
const val NOT_DOWNLOADED_TEXT = "This book is not downloaded"

/**
 * Where a book plays from: a complete download always plays from the phone (Audiobookshelf and Plex alike); anything
 * else streams, but only while [online] and not limited to downloaded books ([downloadedOnly]).
 */
fun choosePlaybackSource(copy: LocalCopy, online: Boolean, downloadedOnly: Boolean): PlaybackSource = when {
    copy == LocalCopy.COMPLETE -> PlaybackSource.Local
    !online -> PlaybackSource.Unavailable(NEEDS_CONNECTION_TO_PLAY_TEXT)
    downloadedOnly -> PlaybackSource.Unavailable(NOT_DOWNLOADED_TEXT)
    else -> PlaybackSource.Stream
}
