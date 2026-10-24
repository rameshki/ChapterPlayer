package com.chapterplayer.data.downloads

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.installDownloadDefaults
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic server and tokens for the downloader tests. Never real data. */
internal fun testServer(token: String = "access-old", refreshToken: String? = "refresh-old") = ServerConfig(
    id = "srv1", source = ServerSource.ABS, name = "Home", baseUrl = "http://abs.test",
    token = token, refreshToken = refreshToken
)

internal class FakeServers(initial: ServerConfig) : ServerRepository {
    val holder = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<ServerConfig>> = holder.map { listOf(it) }
    override suspend fun getById(id: String): ServerConfig? = holder.value.takeIf { it.id == id }
    override suspend fun save(config: ServerConfig) { holder.value = config }
    override suspend fun delete(id: String) {}
    override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
}

/** Bytes 0..n-1 with a pattern, so a wrongly appended file never matches by chance. */
internal fun content(size: Int) = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

/**
 * A fake Audiobookshelf server on a [MockEngine]: answers `/auth/refresh` with new tokens and hands
 * every other request to [files]. Records every request.
 */
internal class FakeAbs(
    var refreshStatus: HttpStatusCode = HttpStatusCode.OK,
    val files: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    val refreshes get() = requests.count { it.url.encodedPath == "/auth/refresh" }
    val fileRequests get() = requests.filter { it.url.encodedPath != "/auth/refresh" }

    private val engine = MockEngine { request ->
        requests += request
        if (request.url.encodedPath == "/auth/refresh") {
            if (refreshStatus != HttpStatusCode.OK) respond("no", refreshStatus)
            else respond(
                """{"user":{"id":"u","username":"x","accessToken":"access-new","refreshToken":"refresh-new"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")
            )
        } else files(request)
    }

    val api = AbsApiService(HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) })
    val downloadClient = HttpClient(engine) { installDownloadDefaults(isDebuggable = false, sink = {}) }

    fun authorizer(servers: ServerRepository) = AbsSessionManager(api, servers)
}

private val RANGE = Regex("bytes=(\\d+)-")

/** The start byte a request asks for, or null when it has no Range header. */
internal fun HttpRequestData.rangeStart(): Long? =
    headers[HttpHeaders.Range]?.let { RANGE.matchEntire(it)?.groupValues?.get(1)?.toLong() }

/** Answers like a server that honours Range: 206 from the asked byte, or 200 with everything. [cutAt] ends the body early. */
internal fun MockRequestHandleScope.serve(body: ByteArray, request: HttpRequestData, cutAt: Int? = null): HttpResponseData {
    val start = request.rangeStart()
    val end = cutAt ?: body.size
    return if (start == null) {
        respond(ByteReadChannel(body.copyOfRange(0, end)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
    } else {
        respond(
            ByteReadChannel(body.copyOfRange(start.toInt(), maxOf(start.toInt(), end))),
            HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentRange, "bytes $start-${body.size - 1}/${body.size}")
        )
    }
}
