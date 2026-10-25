package com.chapterplayer.data.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Opens audio streams with the token of the server the address belongs to. The token is added
 * to each request as it opens (never stored in the address). For Audiobookshelf it is the bearer, and a 401
 * answer goes through the same refresh-once-and-retry-once path as the API calls ([AbsAuthorizer]); a second
 * 401 is a [com.chapterplayer.data.auth.SignInRequiredException], which the loader treats as fatal (no retry
 * loop). For Plex it is the `X-Plex-Token` header; Plex tokens are long lived and cannot be refreshed, so a
 * 401 is at once a [com.chapterplayer.data.auth.SignInRequiredException].
 *
 * Redirects: [DefaultHttpDataSource] follows them inside `open`, so the request header cannot be kept
 * from a redirect target beforehand. What is guaranteed is that no audio is played from another host:
 * when the answer came from a different host than the request, the stream is closed and fails with
 * [UnsafeRedirectException]. Never enable cross-protocol redirects here: that redirect loop resends
 * every request header.
 */
@UnstableApi
class AuthorizingDataSourceFactory(
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        AuthorizingDataSource(DefaultHttpDataSource.Factory().createDataSource(), servers, authorizer)
}

/** The audio stream opened for one request was answered from another host. Fixed text, no address. */
class UnsafeRedirectException : RuntimeException("The audio moved to another address")

/** One request header that carries a token. Its [toString] never shows the value. */
internal class AuthHeader(val name: String, val value: String) {
    override fun toString() = "AuthHeader(name=$name)"
}

/** What the authorizing opener needs from the real HTTP source; a fake stands in for it in tests. */
internal interface Upstream {
    /** Opens the stream with [header] added to the request (none when null). */
    fun open(header: AuthHeader?): Long

    fun close()

    /** The address that answered, after [open]. */
    fun resolvedUrl(): String?

    /** The failure is the server refusing the token (HTTP 401). */
    fun isUnauthorized(e: Exception): Boolean
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
                override fun open(header: AuthHeader?): Long =
                    upstream.open(
                        if (header == null) dataSpec
                        else dataSpec.withAdditionalHeaders(mapOf(header.name to header.value))
                    )

                override fun close() = upstream.close()

                override fun resolvedUrl(): String? = upstream.uri?.toString()

                override fun isUnauthorized(e: Exception) =
                    e is HttpDataSource.InvalidResponseCodeException && e.responseCode == 401
            },
        )

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

/**
 * Opens [url] through [upstream] with the right authorization (see [openAuthorized]): the upstream is
 * closed after any failure, and a stream answered from another host than [url] is closed and refused
 * when the request carried a token.
 */
internal fun openStream(
    url: String,
    servers: ServerRepository,
    authorizer: AbsAuthorizer,
    upstream: Upstream,
): Long = openAuthorized(url, servers, authorizer, upstream::isUnauthorized) { header ->
    try {
        val length = upstream.open(header)
        val answeredFrom = upstream.resolvedUrl()
        if (header != null && answeredFrom != null && !sameHost(url, answeredFrom)) {
            throw UnsafeRedirectException()
        }
        length
    } catch (e: Exception) {
        upstream.close()
        throw e
    }
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
    val match = serverFor(url, configured.map { ServerBearer(it.baseUrl, it.token) }) ?: return open(null)
    val server = configured.first { it.baseUrl == match.baseUrl && it.token == match.token }
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
