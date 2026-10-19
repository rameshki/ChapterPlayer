package com.chapterplayer.data.playback

/**
 * Playback could not start. [message] is written for the person using the app: it never holds a
 * token, an address or a server's raw answer, so it can be shown as it is.
 */
class PlaybackStartException(message: String) : Exception(message.ifBlank { DEFAULT_MESSAGE }) {
    companion object {
        const val DEFAULT_MESSAGE = "Could not start playback"
    }
}
