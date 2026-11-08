package com.chapterplayer.domain.jellyfin

import kotlin.random.Random

/** A book whose item has no media source (no audio file the server can send). */
const val JELLYFIN_NO_AUDIO_TEXT = "The server has no audio file for this book"

/** A book whose length the server does not know, so it cannot be placed on the timeline. */
const val JELLYFIN_LENGTH_UNKNOWN_TEXT = "The server does not know the length of this book"

/**
 * The media type of a Jellyfin container: MP4 family (m4b, m4a, mp4, aac) `audio/mp4`, mp3 `audio/mpeg`, flac
 * `audio/flac`, ogg / oga / opus `audio/ogg`, wav `audio/wav`; anything else is unknown (empty).
 */
fun jellyfinMimeType(container: String?): String = when (container?.trim()?.lowercase()) {
    "m4b", "m4a", "mp4", "aac" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "ogg", "oga", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    else -> ""
}

/** Whether the app can play the container at all (the table of [jellyfinMimeType]); iOS also refuses Ogg (`applePlays`). */
fun jellyfinContainerSupported(container: String?): Boolean = jellyfinMimeType(container).isNotEmpty()

/**
 * The original file of [itemId] (`static=true`, so ranges and seeking work) for the media source [mediaSourceId] and
 * this load's [playSessionId], under [base] with its path kept once. Never a token: it goes in the header.
 */
fun jellyfinStreamUrl(base: String, itemId: String, mediaSourceId: String, playSessionId: String): String =
    base.trimEnd('/') + "/Audio/" + jellyfinUrlPart(itemId) + "/stream?static=true&mediaSourceId=" +
        jellyfinUrlPart(mediaSourceId) + "&playSessionId=" + jellyfinUrlPart(playSessionId)

/** A new random play session id: 32 lower-case hex characters. */
fun newPlaySessionId(random: Random = Random.Default): String {
    val hex = "0123456789abcdef"
    return buildString(32) { repeat(32) { append(hex[random.nextInt(16)]) } }
}
