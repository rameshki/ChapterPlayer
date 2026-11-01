package com.chapterplayer.data.permissions

import com.chapterplayer.domain.downloads.DownloadNotice
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * Local notifications on the iPhone through `UNUserNotificationCenter`. [ensure] asks once (iOS shows its prompt only
 * the first time; later calls return the user's answer at once) and never throws: a download runs either way. [post]
 * shows a notice (a download that finished or failed while the app was in the background); it does nothing without
 * permission. Progress is not a notification on iOS: the app and the continued processing task show it.
 */
class IosNotificationPermission : NotificationPermission {

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun ensure(): Boolean = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }

    /** Shows [notice] now; [id] replaces an earlier notice of the same book. */
    fun post(id: String, notice: DownloadNotice) {
        val content = UNMutableNotificationContent().apply {
            setTitle(notice.title)
            setBody(notice.body)
        }
        val request = UNNotificationRequest.requestWithIdentifier("download-$id", content, trigger = null)
        center.addNotificationRequest(request, withCompletionHandler = null)
    }
}
