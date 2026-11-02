package com.chapterplayer.data.diagnostics

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.domain.plex.classifyConnectionError

/**
 * The server refused the stored token on a Diagnostics read. Unlike the app's own calls, the read neither refreshes the
 * token nor signs the server out, so the page changes nothing even then.
 */
class ReadRefusedException : Exception("The server refused the stored token")

/** The sentence a failed read gets in the report. */
const val READ_REFUSED_TEXT = "The server refused the stored token (this page does not refresh it or sign out; open the book or Settings to sign in again)"

/**
 * A fixed sentence for a failed read: never the exception's own message (it can hold an address or a token), only
 * the exception's type name and, where known, a status code or a connection problem in fixed words.
 */
fun diagnosticsFailureText(error: Throwable): String {
    if (error is ReadRefusedException) return READ_REFUSED_TEXT
    if (error is SignInRequiredException) return "Sign in again in Settings"
    if (error is HttpStatusException) return "The server answered HTTP ${error.status.value}"
    val problem = classifyConnectionError(error)
    if (problem.kind != ProblemKind.OTHER) return "Could not reach the server: ${problem.text}"
    return "The read failed (${error::class.simpleName ?: "unknown error"}); details are not shown"
}
