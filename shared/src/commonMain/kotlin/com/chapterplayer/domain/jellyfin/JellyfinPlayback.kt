package com.chapterplayer.domain.jellyfin

import kotlin.random.Random

/** A book whose item has no media source (no audio file the server can send). */
const val JELLYFIN_NO_AUDIO_TEXT = "The server has no audio file for this book"

/** A book whose length the server does not know, so it cannot be placed on the timeline. */
const val JELLYFIN_LENGTH_UNKNOWN_TEXT = "The server does not know the length of this book"

/**
 * The media type of a Jellyfin container. Jellyfin stores ffprobe's format name, which for MP4-family audio is a comma
 * list such as `mov,mp4,m4a,3gp,3g2,mj2` (other formats are one name, such as `mp3` or `flac`): the parts are split on
 * commas, trimmed, and the first one in the allow-list decides. MP4 family (mov, mp4, m4a, m4b, 3gp, 3g2) `audio/mp4`;
 * raw ADTS aac `audio/aac` (not MP4: forcing the MP4 parser on it would fail); mp3 `audio/mpeg`; flac `audio/flac`;
 * ogg / oga / opus `audio/ogg`; wav `audio/wav`. Anything else is unknown (empty), which the start refuses with the
 * unsupported-format text.
 */
fun jellyfinMimeType(container: String?): String {
    if (container.isNullOrBlank()) return ""
    for (part in container.split(',')) {
        val mime = containerMime(part.trim().lowercase())
        if (mime.isNotEmpty()) return mime
    }
    return ""
}

private fun containerMime(name: String): String = when (name) {
    "mov", "mp4", "m4a", "m4b", "3gp", "3g2" -> "audio/mp4"
    "aac" -> "audio/aac"
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
