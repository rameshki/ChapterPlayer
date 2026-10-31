package com.chapterplayer.data.playback

import androidx.media3.common.PlaybackException

/** Keys of the extras on each media item: where its file sits on the book timeline. */
internal const val EXTRA_START_MS = "chapterplayer.startMs"
internal const val EXTRA_DURATION_MS = "chapterplayer.durationMs"

/**
 * Words for a playback failure that are safe to show: never the player's own message, which can hold
 * an address. [httpStatus] is the server's answer code when the failure was a refused request.
 */
fun safePlaybackError(errorCode: Int, httpStatus: Int?, signInNeeded: Boolean): String = when {
    signInNeeded || httpStatus == 401 || httpStatus == 403 -> SIGN_IN_AGAIN_TEXT
    httpStatus == 404 -> "The audio was not found on the server"
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Cannot reach the server"
    else -> "Playback failed"
}

/** One link of a playback error's cause chain, as far as it is known on the controller's side. */
internal class ErrorLink(val className: String?, val message: String?, val httpStatus: Int? = null)

private val RESPONSE_CODE = Regex("Response code: (\\d{3})")

/**
 * The safe text for a playback error from its [chain]. A refused sign-in is recognised by the exception's
 * class name or by its fixed message anywhere in the chain, and an HTTP answer by its status or by the
 * "Response code: nnn" text, because across the session connection a cause may arrive as a plain exception
 * that only has the original's class name and message.
 */
internal fun describeError(errorCode: Int, chain: List<ErrorLink>): String {
    val signIn = chain.any {
        it.className?.endsWith("SignInRequiredException") == true ||
            it.message?.contains(SIGN_IN_AGAIN_TEXT) == true
    }
    val status = chain.firstNotNullOfOrNull { link ->
        link.httpStatus ?: link.message?.let { RESPONSE_CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
    return safePlaybackError(errorCode, status, signIn)
}
