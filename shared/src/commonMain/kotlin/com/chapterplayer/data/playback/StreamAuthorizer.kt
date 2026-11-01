package com.chapterplayer.data.playback

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The request headers a player sends with every audio request of one book: the token of the server the audio is on
 * (Audiobookshelf: `Authorization: Bearer ...`, Plex: `X-Plex-Token`), or none. Its [toString] never shows a value.
 */
class StreamHeaders(val values: Map<String, String>) {
    val isEmpty: Boolean get() = values.isEmpty()

    override fun toString() = "StreamHeaders(names=${values.keys})"

    override fun equals(other: Any?) = other is StreamHeaders && other.values == values

    override fun hashCode() = values.hashCode()

    companion object {
        val None = StreamHeaders(emptyMap())
    }
}

/** What one check of an audio address found. */
enum class StreamCheck {
    /** The server answered with audio (or a part of it). */
    OK,
    /** The token was refused (401): refreshed once for Audiobookshelf, "Sign in again" for Plex. */
    UNAUTHORIZED,
    /** The server knows the account but does not let it play this (403): no refresh, no sign-in helps. */
    FORBIDDEN,
    NOT_FOUND,
    /** The server sent the request to another address that may not have the token (or from https to http). */
    MOVED_AWAY,
    /** No answer: offline, a timeout, the server down. */
    UNREACHABLE,
    /** Any other answer. */
    FAILED,
}

/** The words shown for a [StreamCheck] that stops playback; null for [StreamCheck.OK]. Never an address. */
fun streamCheckText(check: StreamCheck): String? = when (check) {
    StreamCheck.OK -> null
    StreamCheck.UNAUTHORIZED -> SIGN_IN_AGAIN_TEXT
    StreamCheck.FORBIDDEN -> NOT_ALLOWED_TEXT
    StreamCheck.NOT_FOUND -> AUDIO_NOT_FOUND_TEXT
    StreamCheck.MOVED_AWAY -> AUDIO_MOVED_TEXT
    StreamCheck.UNREACHABLE -> CANNOT_REACH_TEXT
    StreamCheck.FAILED -> PLAYBACK_FAILED_TEXT
}

internal const val AUDIO_NOT_FOUND_TEXT = "The audio was not found on the server"
internal const val AUDIO_MOVED_TEXT = "The audio moved to another address"
internal const val CANNOT_REACH_TEXT = "Cannot reach the server"
internal const val PLAYBACK_FAILED_TEXT = "Playback failed"
internal const val NOT_ALLOWED_TEXT = "The server does not let this account play this book"
internal const val LOCAL_FILE_FAILED_TEXT = "The downloaded file could not be played"

/** How many redirects one check follows (the same as Android's audio requests). */
const val MAX_STREAM_REDIRECTS = 5

/**
 * Where redirect number [hop] (1 for the first) from [from] to [location] (absolute or relative) may go, or null when it
 * must not be followed: past [MAX_STREAM_REDIRECTS], not http(s), from https to http, or, for a request that carries a
 * token ([withToken]), to another scheme, host or port. The same rule as Android's `redirectTarget`, without java.net.
 */
fun streamRedirectTarget(from: String, location: String, hop: Int, withToken: Boolean): String? {
    if (hop > MAX_STREAM_REDIRECTS) return null
    val target = try {
        URLBuilder(from).takeFrom(location.trim()).buildString()
    } catch (_: Exception) {
        return null
    }
    val fromScheme = from.substringBefore("://").lowercase()
    val toScheme = target.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (toScheme != "http" && toScheme != "https") return null
    if (fromScheme == "https" && toScheme == "http") return null
    if (withToken && !sameHost(from, target)) return null
    return target
}

/** One answer of a check: what it means, or the next address to ask. */
sealed interface StreamStep {
    data class Done(val check: StreamCheck) : StreamStep
    data class Follow(val url: String) : StreamStep
}

/** What the answer [status] (with its `Location` header) to a check of [from] means; [hop] counts redirects so far. */
fun streamStep(status: Int, location: String?, from: String, hop: Int, withToken: Boolean): StreamStep = when {
    status in 200..299 -> StreamStep.Done(StreamCheck.OK)
    status == 401 -> StreamStep.Done(StreamCheck.UNAUTHORIZED)
    status == 403 -> StreamStep.Done(StreamCheck.FORBIDDEN)
    status == 404 || status == 410 -> StreamStep.Done(StreamCheck.NOT_FOUND)
    status in 300..399 && !location.isNullOrBlank() ->
        streamRedirectTarget(from, location, hop + 1, withToken)?.let { StreamStep.Follow(it) }
            ?: StreamStep.Done(StreamCheck.MOVED_AWAY)
    else -> StreamStep.Done(StreamCheck.FAILED)
}

/** A start or a recovery that cannot go on; its message is safe to show. */
private class StreamRefused(val check: StreamCheck) : Exception("refused")

/**
 * Gives a platform player (iOS: AVPlayer, which takes fixed request headers per item and cannot ask for a token per
 * request) the headers for a book's audio, and checks the audio address first with one small request of its own
 * (`Range: bytes=0-0`, redirects not followed by the client): a refused token is refreshed once (Audiobookshelf, through
 * [authorizer], as for API calls) or means "Sign in again" (Plex tokens cannot be refreshed), and a redirect is followed
 * only to the same scheme, host and port while the request carries a token ([streamRedirectTarget]), otherwise the start
 * fails with "The audio moved to another address". The player follows redirects itself, so this check is what keeps
 * the token from being sent elsewhere; a server that sends only later requests elsewhere is not caught (documented).
 *
 * [client] must not follow redirects (the probe client). [onPlexRefused] blanks a Plex token the server refused (as the
 * Plex API calls do), so Settings says "Sign in again".
 */
class StreamAuthorizer(
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
    private val client: HttpClient,
    private val onPlexRefused: suspend (ServerConfig) -> Unit = {},
) {

    /**
     * The headers for the audio at [url], after checking it answers. Files on the phone and addresses that belong to no
     * configured server get none (an unknown server is checked too, without a token).
     *
     * @throws PlaybackStartException with a safe message when the audio cannot be played.
     */
    suspend fun prepare(url: String): StreamHeaders {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return StreamHeaders.None
        }
        return try {
            val server = streamServerFor(url, servers.observeAll().first())
            when {
                server == null -> StreamHeaders.None.also { requireOk(check(url, null)) }
                server.source == ServerSource.PLEX -> plex(server, url)
                else -> abs(server, url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlaybackStartException) {
            throw e
        } catch (e: StreamRefused) {
            throw PlaybackStartException(streamCheckText(e.check) ?: PLAYBACK_FAILED_TEXT)
        } catch (e: SignInRequiredException) {
            throw PlaybackStartException(SIGN_IN_AGAIN_TEXT)
        } catch (e: Exception) {
            throw PlaybackStartException(safeStartMessage(e))
        }
    }

    private suspend fun plex(server: ServerConfig, url: String): StreamHeaders {
        val headers = StreamHeaders(mapOf(PLEX_TOKEN_HEADER to server.token))
        val result = check(url, headers)
        if (result == StreamCheck.UNAUTHORIZED) {
            withContext(NonCancellable) { onPlexRefused(server) }
        }
        requireOk(result)
        return headers
    }

    /**
     * The headers for [url] as they should be now, without a check: an Audiobookshelf token close to expiry is refreshed
     * first (through the authorizer), so items built from now on outlive it. Null when there is no server, no token or the
     * refresh failed (the headers in use stay; a failure later rebuilds the item).
     */
    suspend fun currentHeaders(url: String): StreamHeaders? = try {
        val server = streamServerFor(url, servers.observeAll().first())
        when {
            server == null -> null
            server.source == ServerSource.PLEX -> StreamHeaders(mapOf(PLEX_TOKEN_HEADER to server.token))
            else -> authorizer.authorized(server) { bearer -> StreamHeaders(mapOf(HttpHeaders.Authorization to bearer)) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // A refused bearer is refreshed once and checked again (the authorizer's retry); refused twice is "Sign in again".
    private suspend fun abs(server: ServerConfig, url: String): StreamHeaders =
        authorizer.authorized(server) { bearer ->
            val headers = StreamHeaders(mapOf(HttpHeaders.Authorization to bearer))
            val result = check(url, headers)
            if (result == StreamCheck.UNAUTHORIZED) throw HttpStatusException(HttpStatusCode.Unauthorized)
            requireOk(result)
            headers
        }

    private fun requireOk(check: StreamCheck) {
        if (check != StreamCheck.OK) throw StreamRefused(check)
    }

    /** Asks [url] for its first byte with [headers], following allowed redirects by hand. */
    internal suspend fun check(url: String, headers: StreamHeaders?): StreamCheck {
        var current = url
        var hop = 0
        while (true) {
            val (status, location) = try {
                client.prepareGet(current) {
                    headers?.values?.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.Range, "bytes=0-0")
                }.execute { response -> response.status.value to response.headers[HttpHeaders.Location] }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return StreamCheck.UNREACHABLE
            }
            when (val step = streamStep(status, location, current, hop, withToken = headers != null && !headers.isEmpty)) {
                is StreamStep.Done -> return step.check
                is StreamStep.Follow -> {
                    hop++
                    current = step.url
                }
            }
        }
    }
}
