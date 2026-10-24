package com.chapterplayer.data.downloads

import com.chapterplayer.domain.downloads.DownloadVolume

/** The device storage downloads use, as the screens show it. */
interface DownloadStorage {
    /** Free bytes where new downloads go. */
    suspend fun freeBytes(): Long

    /** The places downloads can go now, internal storage first, each with its free space. */
    suspend fun volumes(): List<DownloadVolume>
}
