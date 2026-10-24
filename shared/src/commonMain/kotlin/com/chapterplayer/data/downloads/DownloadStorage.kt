package com.chapterplayer.data.downloads

/** The device storage downloads use, as the screens show it. */
interface DownloadStorage {
    /** Free bytes where new downloads go. */
    suspend fun freeBytes(): Long
}
