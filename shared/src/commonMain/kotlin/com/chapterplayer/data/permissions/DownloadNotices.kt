package com.chapterplayer.data.permissions

import com.chapterplayer.domain.downloads.DownloadNotice

/** Notification permission plus the notice a platform shows when a download ends in the background (iOS). */
interface DownloadNotices : NotificationPermission {
    /** Shows [notice] for the book [id] (a later notice of the same book replaces it); nothing without permission. */
    fun post(id: String, notice: DownloadNotice)
}

/** No notices and no permission prompt (tests). */
object NoDownloadNotices : DownloadNotices {
    override suspend fun ensure(): Boolean = false
    override fun post(id: String, notice: DownloadNotice) = Unit
}
