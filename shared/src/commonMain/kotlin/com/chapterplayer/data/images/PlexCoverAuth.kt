package com.chapterplayer.data.images

import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.domain.plex.plexTokenFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The signed-in Plex servers, kept up to date for the image loader, so a cover does not read the server list (and
 * decrypt its tokens) each time. A failing server list counts as no Plex servers: covers then load without a token.
 */
class PlexImageServers(servers: ServerRepository, scope: CoroutineScope) {
    private val current: StateFlow<List<ServerConfig>?> = servers.observeAll()
        .map { list -> list.filter { it.source == ServerSource.PLEX && it.token.isNotBlank() } }
        .catch { e -> if (e is CancellationException) throw e else emit(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The Plex servers; waits for the first read of the list, so the first covers already get their token. */
    suspend fun plexServers(): List<ServerConfig> = current.filterNotNull().first()
}

/**
 * [headers] with the token of the Plex server [data] (the image address) is on, or null when it is on none: Plex covers
 * are plain addresses and the token travels in the `X-Plex-Token` header, as it does for every other Plex request.
 */
fun plexCoverHeaders(data: Any?, headers: NetworkHeaders, servers: List<ServerConfig>): NetworkHeaders? {
    val url = data as? String ?: return null
    val token = plexTokenFor(url, servers) ?: return null
    return headers.newBuilder().set(PLEX_TOKEN_HEADER, token).build()
}

/** Adds the Plex token header to covers on a signed-in Plex server; every other image is left as it is. */
class PlexCoverInterceptor(private val servers: suspend () -> List<ServerConfig>) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.data !is String) return chain.proceed()
        val headers = plexCoverHeaders(request.data, request.httpHeaders, servers()) ?: return chain.proceed()
        return chain.withRequest(request.newBuilder().httpHeaders(headers).build()).proceed()
    }
}
