package com.chapterplayer.data.remote.plex

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.sync.PlexConnectionProbe
import com.chapterplayer.domain.plex.ConnectionProblem
import com.chapterplayer.domain.plex.ProblemKind
import com.chapterplayer.domain.plex.classifyConnectionError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PlexIdentityDto(@SerialName("MediaContainer") val mediaContainer: PlexIdentityContainer? = null)

@Serializable
internal data class PlexIdentityContainer(@SerialName("machineIdentifier") val machineIdentifier: String? = null)

/**
 * Probes one Plex address with `GET <uri>/identity`, which needs no token, so none is sent: an address that turns out
 * to be another device (a captive portal, another Plex server on this network) never sees it. The answer must name the
 * expected server ([PlexConnectionProbe.probe]'s machine id); a success status alone is not enough. [client] must not
 * follow redirects and must leave statuses to this code (`createProbeHttpClient`).
 */
class KtorPlexConnectionProbe(private val client: HttpClient) : PlexConnectionProbe {
    override suspend fun probe(uri: String, machineId: String?): ConnectionProblem? = try {
        val response = client.get(uri.trim().trimEnd('/') + "/identity") {
            header(HttpHeaders.Accept, "application/json")
        }
        if (!response.status.isSuccess()) {
            ConnectionProblem(ProblemKind.HTTP, response.status.value)
        } else {
            val answered = try {
                ApiJson.decodeFromString(PlexIdentityDto.serializer(), response.bodyAsText()).mediaContainer?.machineIdentifier
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when {
                machineId == null && answered != null -> null
                machineId != null && answered.equals(machineId, ignoreCase = true) -> null
                else -> ConnectionProblem(ProblemKind.WRONG_SERVER)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        classifyConnectionError(e)
    }
}
