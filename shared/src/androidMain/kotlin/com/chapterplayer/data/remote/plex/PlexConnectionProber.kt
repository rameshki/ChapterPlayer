package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.sync.PlexConnectionProbe
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.domain.plex.classifyConnectionError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Probes one Plex address with `GET <uri>/identity` (the token in its header). [client] must not follow redirects and
 * must leave statuses to this code (`createProbeHttpClient`): a redirect or any other answer than success is a failure.
 */
class KtorPlexConnectionProbe(private val client: HttpClient) : PlexConnectionProbe {
    override suspend fun probe(uri: String, token: String): ConnectionProblem? = try {
        val response = client.get(uri.trim().trimEnd('/') + "/identity") {
            header(PLEX_TOKEN_HEADER, token)
            header(HttpHeaders.Accept, "application/json")
        }
        if (response.status.isSuccess()) null else ConnectionProblem(ProblemKind.HTTP, response.status.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        classifyConnectionError(e)
    }
}
