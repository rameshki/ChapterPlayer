package com.chapterplayer.data.remote

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException

/** Thrown for any non-success HTTP status. The message is what people see in the sync error. */
internal class HttpStatusException(val status: HttpStatusCode) :
    IOException("HTTP ${status.value} ${status.description}")

/**
 * How long to wait. The defaults are the 30 seconds the app has always used for API calls.
 * Very long transfers (Stage 3 downloads) need a different configuration: no overall limit,
 * only a check for a stalled connection.
 */
internal data class HttpTimeouts(
    val connectMillis: Long = 30_000,
    val socketMillis: Long = 30_000,
)

private const val LOG_TAG = "ChapterPlayerHttp"

/** The one HTTP client for every server, using the OkHttp engine. */
internal fun createHttpClient(isDebuggable: Boolean): HttpClient =
    HttpClient(OkHttp) { installApiDefaults(isDebuggable) }

/**
 * Settings shared by the app and its tests: JSON bodies, timeouts, errors for non-success
 * statuses, and debug logging with the tokens hidden.
 */
internal fun HttpClientConfig<*>.installApiDefaults(
    isDebuggable: Boolean,
    timeouts: HttpTimeouts = HttpTimeouts(),
    sink: (String) -> Unit = { Log.d(LOG_TAG, it) },
) {
    install(ContentNegotiation) { json(ApiJson) }

    install(HttpTimeout) {
        connectTimeoutMillis = timeouts.connectMillis
        socketTimeoutMillis = timeouts.socketMillis
    }

    // Debug builds only. Auth headers are never logged (project rule).
    install(Logging) {
        logger = object : Logger {
            override fun log(message: String) = sink(message)
        }
        level = if (isDebuggable) LogLevel.BODY else LogLevel.NONE
        sanitizeHeader { name ->
            name.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                name.equals("X-Plex-Token", ignoreCase = true)
        }
    }

    HttpResponseValidator {
        validateResponse { response ->
            if (!response.status.isSuccess()) throw HttpStatusException(response.status)
        }
    }
}
