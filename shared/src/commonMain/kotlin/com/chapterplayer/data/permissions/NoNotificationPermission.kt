package com.chapterplayer.data.permissions

/** For a platform that shows no notifications yet (iOS until downloads exist): never asks, and says no. */
object NoNotificationPermission : NotificationPermission {
    override suspend fun ensure(): Boolean = false
}
