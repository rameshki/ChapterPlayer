package com.chapterplayer.data.playback

import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.JELLYFIN_DEVICE_NAME
import com.chapterplayer.domain.jellyfin.JellyfinClient
import com.chapterplayer.domain.jellyfin.jellyfinAuthHeader
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId

/**
 * The `Authorization: MediaBrowser ...` value an audio request to a Jellyfin server carries: the same client fields and
 * row DeviceId as its API calls, with the row's token. Given to both players' authorizers, so neither reaches Koin.
 */
fun jellyfinStreamHeader(installId: InstallId, appInfo: AppInfo): suspend (ServerConfig) -> String = { server ->
    jellyfinAuthHeader(
        JellyfinClient(JELLYFIN_DEVICE_NAME, jellyfinDeviceId(installId.value, server.id), appInfo.versionName),
        server.token,
    )
}
