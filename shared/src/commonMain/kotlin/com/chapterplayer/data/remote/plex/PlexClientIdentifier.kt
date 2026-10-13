package com.chapterplayer.data.remote.plex

/** A stable id this installation sends to Plex so it recognises the same app on each sign-in. */
interface PlexClientIdentifier {
    val value: String
}
