package com.chapterplayer.data.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Opens audio streams with the bearer token of the server the address belongs to. The token is added
 * to each request as it opens (never stored in the address). A 401 answer goes through the same
 * refresh-once-and-retry-once path as the API calls ([AbsAuthorizer]); a second 401 is a
 * [com.chapterplayer.data.auth.SignInRequiredException], which the loader treats as fatal (no retry loop).
 *
 * Redirects: [DefaultHttpDataSource] lets the platform follow them, so the header is not re-added by us.
 * Never enable cross-protocol redirects here: its own redirect loop resends every request header.
 */
@UnstableApi
class AuthorizingDataSourceFactory(
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        AuthorizingDataSource(DefaultHttpDataSource.Factory().createDataSource(), servers, authorizer)
}

@UnstableApi
private class AuthorizingDataSource(
    private val upstream: HttpDataSource,
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long =
        openAuthorized(
            url = dataSpec.uri.toString(),
            servers = servers,
            authorizer = authorizer,
            isUnauthorized = { it is HttpDataSource.InvalidResponseCodeException && it.responseCode == 401 },
        ) { authorization ->
            val spec = if (authorization == null) dataSpec
            else dataSpec.withAdditionalHeaders(mapOf("Authorization" to authorization))
            try {
                upstream.open(spec)
            } catch (e: Exception) {
                upstream.close()
                throw e
            }
        }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

/**
 * Runs [open] for a request to [url]. For an address that belongs to a configured Audiobookshelf server,
 * [open] gets the full header value ("Bearer ...") and runs through [authorizer], so an expiring token is
 * refreshed first and a 401 (see [isUnauthorized]) refreshes once and retries once. Any other address
 * gets [open] with null. Blocking, for the player's loader thread.
 */
internal fun <T> openAuthorized(
    url: String,
    servers: ServerRepository,
    authorizer: AbsAuthorizer,
    isUnauthorized: (Exception) -> Boolean,
    open: (authorization: String?) -> T,
): T {
    val configured = runBlocking { servers.observeAll().first() }.filter { it.source == ServerSource.ABS }
    val match = serverFor(url, configured.map { ServerBearer(it.baseUrl, it.token) }) ?: return open(null)
    val server = configured.first { it.baseUrl == match.baseUrl && it.token == match.token }
    return runBlocking {
        authorizer.authorized(server) { bearer ->
            try {
                open(bearer)
            } catch (e: Exception) {
                if (isUnauthorized(e)) throw HttpStatusException(HttpStatusCode.Unauthorized)
                throw e
            }
        }
    }
}
