package com.chapterplayer.domain.jellyfin

/*
 * The `Authorization: MediaBrowser ...` header Jellyfin reads on every call. The server URL-decodes each value, so the
 * client fields keep only letters, digits, `-`, `.` and spaces, and the token is percent-encoded. The token never goes
 * into a URL (no `api_key` / `ApiKey`), and no `X-Emby-*` header is sent.
 */

/** What the app tells Jellyfin about itself. [device] is a generic "Android" / "iPhone", never a personal name. */
data class JellyfinClient(
    val device: String,
    val deviceId: String,
    val version: String,
    val client: String = "ChapterPlayer",
)

/** [raw] with every character but letters, digits, `-`, `.` and spaces dropped. */
fun jellyfinHeaderValue(raw: String): String =
    raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '.' || it == '-' }

/**
 * The DeviceId for one server row on this install: unique per install and per row, so signing in on one row (or on
 * another phone) never signs another out. Computed, never stored.
 */
fun jellyfinDeviceId(installId: String, serverRowId: String): String =
    jellyfinHeaderValue("chapterplayer-$installId-$serverRowId")

/** The header value (the header name is `Authorization`); no `Token` part when [token] is null or blank. */
fun jellyfinAuthHeader(client: JellyfinClient, token: String?): String {
    val parts = mutableListOf(
        "Client" to jellyfinHeaderValue(client.client),
        "Device" to jellyfinHeaderValue(client.device),
        "DeviceId" to jellyfinHeaderValue(client.deviceId),
        "Version" to jellyfinHeaderValue(client.version),
    )
    if (!token.isNullOrBlank()) parts += "Token" to percentEncode(token)
    return "MediaBrowser " + parts.joinToString(", ") { (name, value) -> "$name=\"$value\"" }
}

private const val HEX = "0123456789ABCDEF"

/** Percent-encodes every byte of [value] (UTF-8) except the unreserved characters `A-Z a-z 0-9 - . _ ~`. */
private fun percentEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
            append(c)
        } else {
            append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
        }
    }
}
