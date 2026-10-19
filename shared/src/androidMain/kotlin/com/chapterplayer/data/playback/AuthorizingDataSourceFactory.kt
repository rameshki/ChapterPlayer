package com.chapterplayer.data.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Opens audio streams with the bearer token of the server the address belongs to. The token is added
 * to each request as it opens (never stored in the address), and is asked for again every time a
 * stream (re)opens, so a long book keeps working after the access token expires.
 *
 * A refused sign-in is thrown as [com.chapterplayer.data.auth.SignInRequiredException]: the loader
 * treats it as fatal, so playback stops with an error instead of retrying in a loop.
 */
@UnstableApi
class AuthorizingDataSourceFactory(
    private val servers: ServerRepository,
    private val authorizer: AbsAuthorizer,
) : DataSource.Factory {

    private val upstream = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(false)

    override fun createDataSource(): DataSource =
        ResolvingDataSource(upstream.createDataSource(), ResolvingDataSource.Resolver { resolve(it) })

    private fun resolve(dataSpec: DataSpec): DataSpec {
        // Runs on the player's loader thread, so blocking here is fine.
        val configured = runBlocking { servers.observeAll().first() }
        val match = serverFor(dataSpec.uri.toString(), configured.map { ServerBearer(it.baseUrl, it.token) })
            ?: return dataSpec
        val server = configured.first { it.baseUrl == match.baseUrl && it.token == match.token }
        val bearer = runBlocking { authorizer.authorized(server) { it } }
        return dataSpec.withAdditionalHeaders(mapOf("Authorization" to bearer))
    }
}
