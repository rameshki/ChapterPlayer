package com.chapterplayer.data.permissions

/** Permission to show notifications (Android 13 and later ask for it). */
interface NotificationPermission {
    /**
     * Asks for the permission if it is missing and has not been asked for here before; returns whether
     * notifications may be shown. Never throws for a refusal: what needed it carries on without a notification.
     */
    suspend fun ensure(): Boolean
}
