package com.chapterplayer.data.remote

/**
 * A stable id of this installation (kept by the platform). Plex's client identifier is the one stored value; Jellyfin's
 * DeviceId is built from it per server row (`jellyfinDeviceId`), so nothing new is stored.
 */
interface InstallId {
    val value: String
}
