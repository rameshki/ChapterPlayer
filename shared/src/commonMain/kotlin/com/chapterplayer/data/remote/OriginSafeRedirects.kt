package com.chapterplayer.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.isSecure
import io.ktor.http.takeFrom

/** How many redirects one image request may follow. */
internal const val MAX_REDIRECTS = 5

private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/** The headers that carry a server's credentials; they are only ever sent to the server they belong to. */
private val CREDENTIAL_HEADERS = listOf(HttpHeaders.Authorization, "X-Plex-Token")

/** Same scheme, host and port: the token of one may go to the other. */
internal fun sameOrigin(a: Url, b: Url): Boolean =
    a.protocol.name.equals(b.protocol.name, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

/**
 * Follows redirects in place of Ktor's own redirect handling (create the client with `followRedirects = false`), the
 * same way (relative locations, no https-to-http step down, at most [MAX_REDIRECTS]), except that a redirect that
 * leaves the origin drops every credential header ([CREDENTIAL_HEADERS]): Ktor's own handling only drops
 * `Authorization`, so a Plex token would follow a cover to another host. A same-origin redirect keeps them.
 */
internal fun HttpClient.followRedirectsKeepingCredentialsAtHome(): HttpClient = apply {
    plugin(HttpSend).intercept { request ->
        var builder = request
        var call = execute(builder)
        repeat(MAX_REDIRECTS) {
            val status = call.response.status.value
            if (status !in REDIRECT_STATUSES) return@intercept call
            val location = call.response.headers[HttpHeaders.Location] ?: return@intercept call
            val from = call.request.url
            val next = HttpRequestBuilder().apply {
                takeFrom(builder)
                url.parameters.clear()
                url.takeFrom(location)
                if (status == 303) method = HttpMethod.Get
            }
            val to = next.url.build()
            if (from.protocol.isSecure() && !to.protocol.isSecure()) return@intercept call
            if (!sameOrigin(from, to)) CREDENTIAL_HEADERS.forEach { next.headers.remove(it) }
            builder = next
            call = execute(builder)
        }
        call
    }
}

/** The client covers load with: the platform engine, with redirects followed by [followRedirectsKeepingCredentialsAtHome]. */
internal fun createImageHttpClient(): HttpClient =
    HttpClient(createHttpEngine()) { followRedirects = false }.followRedirectsKeepingCredentialsAtHome()
