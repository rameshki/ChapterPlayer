package com.chapterplayer.data.playback

import androidx.media3.common.PlaybackException

/** A configured server's address and the access token to use against it. Never logged. */
class ServerBearer(val baseUrl: String, val token: String) {
    override fun toString() = "ServerBearer(baseUrl=$baseUrl)"
}

/**
 * The server a request address belongs to: the one with the longest base address that is a prefix of
 * [url] (on a path boundary). Scheme and host are compared without regard to case, a trailing slash on
 * the base is ignored, and the port must match. Servers without a token are skipped. Null when none matches.
 */
fun serverFor(url: String, servers: List<ServerBearer>): ServerBearer? {
    val target = normalizeAddress(url)
    var best: ServerBearer? = null
    var bestLength = -1
    for (server in servers) {
        if (server.token.isBlank()) continue
        val base = normalizeAddress(server.baseUrl).trimEnd('/')
        if (base.isEmpty() || base.length <= bestLength) continue
        val matches = target == base ||
            (target.startsWith(base) && target[base.length].let { it == '/' || it == '?' || it == '#' })
        if (matches) {
            best = server
            bestLength = base.length
        }
    }
    return best
}

/** The access token for the server [url] belongs to, or null when no server matches. See [serverFor]. */
fun bearerFor(url: String, servers: List<ServerBearer>): String? = serverFor(url, servers)?.token

// Lower-cases the scheme and the host (with its port); the path stays as written.
private fun normalizeAddress(address: String): String {
    val trimmed = address.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed
    val authorityStart = schemeEnd + 3
    var authorityEnd = authorityStart
    while (authorityEnd < trimmed.length && trimmed[authorityEnd] !in "/?#") authorityEnd++
    return trimmed.substring(0, authorityEnd).lowercase() + trimmed.substring(authorityEnd)
}

/**
 * Words for a playback failure that are safe to show: never the player's own message, which can hold
 * an address. [httpStatus] is the server's answer code when the failure was a refused request.
 */
fun safePlaybackError(errorCode: Int, httpStatus: Int?, signInNeeded: Boolean): String = when {
    signInNeeded || httpStatus == 401 || httpStatus == 403 -> "Sign in again in Settings"
    httpStatus == 404 -> "The audio was not found on the server"
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Cannot reach the server"
    else -> "Playback failed"
}
