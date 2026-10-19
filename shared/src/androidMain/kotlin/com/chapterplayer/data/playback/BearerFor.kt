package com.chapterplayer.data.playback

import androidx.media3.common.PlaybackException

/** Keys of the extras on each media item: where its file sits on the book timeline. */
internal const val EXTRA_START_MS = "chapterplayer.startMs"
internal const val EXTRA_DURATION_MS = "chapterplayer.durationMs"

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

private val OWN_START_MESSAGES = setOf(
    "Unknown book",
    "The book's server is no longer configured",
    "This server cannot play books yet",
)

/**
 * A fixed, safe text for why a playback session could not start. Raw exception text can hold an address
 * or a piece of the server's answer, so only the repository's own fixed messages pass through.
 */
internal fun safeStartMessage(failure: Throwable): String = when {
    failure is com.chapterplayer.data.auth.SignInRequiredException -> "Sign in again in Settings"
    failure is com.chapterplayer.data.remote.HttpStatusException -> when (failure.status.value) {
        401, 403 -> "Sign in again in Settings"
        404 -> "This book is no longer on the server"
        else -> "The server could not start playback"
    }
    failure is java.io.IOException -> "Cannot reach the server"
    failure.message in OWN_START_MESSAGES -> failure.message!!
    else -> PlaybackStartException.DEFAULT_MESSAGE
}
