package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.DownloadState

/**
 * Determines if a book is playable based on its download state, online status, and downloaded only mode.
 */
fun isPlayable(state: DownloadState, online: Boolean, downloadedOnly: Boolean): Boolean {
    return when (state) {
        DownloadState.DOWNLOADED -> true
        else -> online && !downloadedOnly
    }
}

/**
 * Determines if a book should be dimmed based on its download state, online status, and downloaded only mode.
 */
fun isDimmed(state: DownloadState, online: Boolean, downloadedOnly: Boolean): Boolean {
    return !isPlayable(state, online, downloadedOnly)
}

/**
 * The offline banner (with the "Downloaded only" switch) shows while offline, and also while the
 * setting is on, so it can always be turned off.
 */
fun showOfflineBanner(online: Boolean, downloadedOnly: Boolean): Boolean = !online || downloadedOnly
