package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.InstallId

/**
 * A stable id this installation sends to Plex so it recognises the same app on each sign-in. It is also the app's
 * [InstallId] (Jellyfin's DeviceId is built from it), so its stored value never changes.
 */
interface PlexClientIdentifier : InstallId {
    override val value: String
}
