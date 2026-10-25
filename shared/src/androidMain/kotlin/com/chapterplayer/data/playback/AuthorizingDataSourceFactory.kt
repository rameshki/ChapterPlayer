package com.chapterplayer.data.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.domain.plex.plexAddressesOf
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.URI

/**
 * Opens audio streams with the token of the server the address belongs to. The token is added
 * to each request as it opens (never stored in the address). For Audiobookshelf it is the bearer, and a 401
 * answer goes through the same refresh-once-and-retry-once path as the API calls ([AbsAuthorizer]); a second
 * 401 is a [com.chapterplayer.data.auth.SignInRequiredException], which the loader treats as fatal (no retry
 * loop). For Plex it is the `X-Plex-Token` header; Plex tokens are long lived and cannot be refreshed, so a
 * 401 is at once a [com.chapterplayer.data.auth.SignInRequiredException].
 *
 * Redirects: the HTTP client follows none itself ([OkHttpDataSource] over an OkHttp client with redirects
 * off); [openStream] follows them by hand ([redirectTarget]): at most [MAX_AUDIO_REDIRECTS], never from
 * https to http, and with a token header only to the same scheme, host and port, so the header never
 * reaches another host. A request with a token that is sent elsewhere fails with [UnsafeRedirectException].
 */
@UnstableApi
class AuthorizingDataSourceFactory(
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
) : DataSource.Factory {

    private val http = OkHttpDataSource.Factory(
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    )

    override fun createDataSource(): DataSource =
        AuthorizingDataSource(http.createDataSource(), servers, authorizer)
}

/** The audio stream opened for one request was answered from another host. Fixed text, no address. */
class UnsafeRedirectException : RuntimeException("The audio moved to another address")

/** One request header that carries a token. Its [toString] never shows the value. */
internal class AuthHeader(val name: String, val value: String) {
    override fun toString() = "AuthHeader(name=$name)"
}

/** What the authorizing opener needs from the real HTTP source; a fake stands in for it in tests. */
internal interface Upstream {
    /** Opens [url] with [header] added to the request (none when null). Redirects are not followed. */
    fun open(url: String, header: AuthHeader?): Long

    fun close()

    /** The address that answered, after [open]. */
    fun resolvedUrl(): String?

    /** The failure is the server refusing the token (HTTP 401). */
    fun isUnauthorized(e: Exception): Boolean

    /** When the failure is a redirect answer (3xx), its `Location`; null otherwise. */
    fun redirectLocation(e: Exception): String?
}

@UnstableApi
private class AuthorizingDataSource(
    private val upstream: HttpDataSource,
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long =
        openStream(
            url = dataSpec.uri.toString(),
            servers = servers,
            authorizer = authorizer,
            upstream = object : Upstream {
                override fun open(url: String, header: AuthHeader?): Long {
                    val spec = dataSpec.withUri(Uri.parse(url))
                    return upstream.open(if (header == null) spec else spec.withAdditionalHeaders(mapOf(header.name to header.value)))
                }

                override fun close() = upstream.close()

                override fun resolvedUrl(): String? = upstream.uri?.toString()

                override fun isUnauthorized(e: Exception) =
                    e is HttpDataSource.InvalidResponseCodeException && e.responseCode == 401

                override fun redirectLocation(e: Exception): String? {
                    if (e !is HttpDataSource.InvalidResponseCodeException || e.responseCode !in 300..399) return null
                    return e.headerFields.entries.firstOrNull { it.key.equals("Location", ignoreCase = true) }?.value?.firstOrNull()
                }
            },
        )

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

/** How many redirects one audio request follows. */
internal const val MAX_AUDIO_REDIRECTS = 5

/**
 * Where redirect number [hop] (1 for the first) from [from] to [location] (absolute or relative) goes, or null when it
 * must not be followed: past [MAX_AUDIO_REDIRECTS], not http(s), from https to http, or, for a request that carries a
 * token ([withToken]), to another scheme, host or port.
 */
internal fun redirectTarget(from: String, location: String, hop: Int, withToken: Boolean): String? {
    if (hop > MAX_AUDIO_REDIRECTS) return null
    val target = try {
        URI(from).resolve(location.trim()).toString()
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

/**
 * Opens [url] through [upstream] with the right authorization (see [openAuthorized]), following redirects by hand
 * ([redirectTarget]); a redirect that may not be followed fails with [UnsafeRedirectException]. The upstream is closed
 * after any failure, and a stream answered from another host than asked is closed and refused when the request
 * carried a token.
 */
internal fun openStream(
    url: String,
    servers: ServerRepository,
    authorizer: AbsAuthorizer,
    upstream: Upstream,
): Long = openAuthorized(url, servers, authorizer, upstream::isUnauthorized) { header ->
    var current = url
    var hop = 0
    var length: Long? = null
    while (length == null) {
        try {
            val opened = upstream.open(current, header)
            val answeredFrom = upstream.resolvedUrl()
            if (header != null && answeredFrom != null && !sameHost(current, answeredFrom)) {
                throw UnsafeRedirectException()
            }
            length = opened
        } catch (e: Exception) {
            upstream.close()
            val location = upstream.redirectLocation(e) ?: throw e
            hop++
            current = redirectTarget(current, location, hop, withToken = header != null) ?: throw UnsafeRedirectException()
        }
    }
    length
}

/**
 * Runs [open] for a request to [url]. For an address that belongs to a configured Audiobookshelf server,
 * [open] gets the Authorization header ("Bearer ...") and runs through [authorizer], so an expiring token is
 * refreshed first and a 401 (see [isUnauthorized]) refreshes once and retries once. An address under a Plex
 * server gets its `X-Plex-Token` header, and a 401 there is a [SignInRequiredException] (no refresh exists).
 * Any other address gets [open] with null. Blocking, for the player's loader thread.
 */
internal fun <T> openAuthorized(
    url: String,
    servers: ServerRepository,
    authorizer: AbsAuthorizer,
    isUnauthorized: (Exception) -> Boolean,
    open: (header: AuthHeader?) -> T,
): T {
    val configured = runBlocking { servers.observeAll().first() }
        .filter { it.source == ServerSource.ABS || it.source == ServerSource.PLEX }
    // A Plex server answers on each connection plex.tv listed for it, so each is one of its addresses.
    val bearers = configured.flatMap { s -> plexAddressesOf(s).map { address -> s to ServerBearer(address, s.token) } }
    val match = serverFor(url, bearers.map { it.second }) ?: return open(null)
    val server = bearers.first { it.second === match }.first
    if (server.source == ServerSource.PLEX) {
        return try {
            open(AuthHeader(PLEX_TOKEN_HEADER, server.token))
        } catch (e: Exception) {
            if (isUnauthorized(e)) throw SignInRequiredException(server.name)
            throw e
        }
    }
    return runBlocking {
        authorizer.authorized(server) { bearer ->
            try {
                open(AuthHeader("Authorization", bearer))
            } catch (e: Exception) {
                if (isUnauthorized(e)) throw HttpStatusException(HttpStatusCode.Unauthorized)
                throw e
            }
        }
    }
}

/** Whether two addresses have the same scheme, host and port (case-insensitive). */
internal fun sameHost(a: String, b: String): Boolean = originOf(a) == originOf(b)

private fun originOf(address: String): String {
    val trimmed = address.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed.lowercase()
    var end = schemeEnd + 3
    while (end < trimmed.length && trimmed[end] !in "/?#") end++
    return trimmed.substring(0, end).lowercase()
}
