package com.chapterplayer.testing

import com.chapterplayer.data.remote.plex.PlexClientIdentifier

/** A fixed, synthetic Plex client id for tests. */
object TestPlexClientId : PlexClientIdentifier {
    override val value = "test-client-id"
}
