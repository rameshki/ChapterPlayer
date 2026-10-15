package com.chapterplayer.data.auth

/**
 * Cleans up a typed server address: trims, adds `http://` when no scheme is given, lower-cases
 * the scheme and host only, keeps a sub-path, and drops trailing slashes, a query and a fragment.
 * Returns null when the input is empty or not a plausible address.
 */
fun normalizeServerAddress(input: String): String? {
    var text = input.trim()
    if (text.isEmpty()) return null
    if ("://" !in text) text = "http://$text"
    val schemeEnd = text.indexOf("://")
    val scheme = text.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    var rest = text.substring(schemeEnd + 3)
    val cut = rest.indexOfFirst { it == '?' || it == '#' }
    if (cut >= 0) rest = rest.substring(0, cut)
    val slash = rest.indexOf('/')
    val authority = if (slash >= 0) rest.substring(0, slash) else rest
    val path = if (slash >= 0) rest.substring(slash).trimEnd('/') else ""
    if (authority.isEmpty() || path.any { it.isWhitespace() }) return null

    val host: String
    val port: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        host = authority.substring(0, close + 1)
        val after = authority.substring(close + 1)
        port = when {
            after.isEmpty() -> null
            after.startsWith(":") -> after.substring(1)
            else -> return null
        }
        val inner = host.substring(1, host.length - 1)
        if (inner.isEmpty() || !inner.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
    } else {
        val colon = authority.lastIndexOf(':')
        host = if (colon >= 0) authority.substring(0, colon) else authority
        port = if (colon >= 0) authority.substring(colon + 1) else null
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        if (host.startsWith(".") || host.endsWith(".")) return null
    }
    if (port != null) {
        val number = port.toIntOrNull()
        if (!port.all { it in '0'..'9' } || number == null || number !in 1..65535) return null
    }
    return buildString {
        append(scheme).append("://").append(host.lowercase())
        if (port != null) append(':').append(port)
        append(path)
    }
}

data class AbsLibraryInfo(val id: String, val name: String, val mediaType: String)

/** The outcome of a successful sign-in. Its [toString] leaves the tokens out. */
data class AbsSignInResult(
    val baseUrl: String,
    val token: String,
    val refreshToken: String?,
    val libraries: List<AbsLibraryInfo>,
) {
    override fun toString() = "AbsSignInResult(baseUrl=$baseUrl, libraries=${libraries.size})"
}

sealed interface AbsSignInError {
    data object Unreachable : AbsSignInError
    data object NotAudiobookshelf : AbsSignInError
    data object LocalLoginOff : AbsSignInError
    data object WrongCredentials : AbsSignInError
    data object TooManyAttempts : AbsSignInError

    /** The token or API key was refused. */
    data object Rejected : AbsSignInError
    data object NoBookLibraries : AbsSignInError
    data class ServerError(val code: Int) : AbsSignInError
}

/** The only exception sign-in throws. The message is fixed so no server text, password or token leaks. */
class AbsSignInException(val error: AbsSignInError) : Exception("Sign-in failed")

interface AbsSignInPort {
    /** Throws only [AbsSignInException]. */
    suspend fun withPassword(address: String, username: String, password: String): AbsSignInResult

    /** Throws only [AbsSignInException]. */
    suspend fun withApiKey(address: String, apiKey: String): AbsSignInResult
}
