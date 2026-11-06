package com.chapterplayer.data.images

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.followRedirectsKeepingCredentialsAtHome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverBytesLoaderTest {

    private val abs = ServerConfig(id = "a", source = ServerSource.ABS, name = "ABS", baseUrl = "https://abs.test", token = "abs-secret")
    private val plex = ServerConfig(id = "p", source = ServerSource.PLEX, name = "Plex", baseUrl = "https://plex.test:32400", token = "plex-secret")

    private val books = mapOf(
        "abs_1" to BookCover("a", "https://abs.test/api/items/1/cover"),
        "abs_2" to BookCover("a", "https://abs.test/api/items/2/cover"),
        "abs_far" to BookCover("a", "https://elsewhere.test/api/items/3/cover"),
        "abs_none" to BookCover("a", null),
        "plex_7" to BookCover("p", "https://plex.test:32400/library/metadata/7/thumb/1"),
        "abs_gone" to BookCover("removed", "https://abs.test/api/items/9/cover"),
    )

    private val image = byteArrayOf(1, 2, 3, 4)

    private class CountingAuthorizer(private val bearer: String = "Bearer abs-secret") : AbsAuthorizer {
        var calls = 0
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T {
            calls++
            return call(bearer)
        }
    }

    private class Rig(
        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request -> requests += request; handler(request) }) { followRedirects = false }
            .followRedirectsKeepingCredentialsAtHome()
    }

    private var now = 0L

    private fun loader(
        rig: Rig,
        authorizer: AbsAuthorizer = CountingAuthorizer(),
        local: Map<String, ByteArray> = emptyMap(),
        downscale: (ByteArray) -> ByteArray? = { it + 9 },
        timeoutMs: Long = 5_000,
    ) = CoverBytesLoader(
        coverOf = { id -> books[id] },
        server = { id -> listOf(abs, plex).firstOrNull { it.id == id } },
        authorizer = authorizer,
        localCover = { id -> local[id] },
        downscale = downscale,
        client = rig.client,
        io = Dispatchers.Unconfined,
        nowMs = { now },
        timeoutMs = timeoutMs,
    )

    private fun ok(scope: MockRequestHandleScope) = scope.respond(image, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))

    @Test
    fun `an Audiobookshelf cover is fetched with the bearer in a header and scaled`() = runBlocking {
        val rig = Rig { ok(this) }
        val bytes = loader(rig).load("abs_1")
        assertContentEquals(image + 9, bytes)
        val request = rig.requests.single()
        assertEquals("https://abs.test/api/items/1/cover", request.url.toString())
        assertEquals("Bearer abs-secret", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["X-Plex-Token"])
    }

    @Test
    fun `a Plex cover gets the token header and never the token in the address`() = runBlocking {
        val rig = Rig { ok(this) }
        assertContentEquals(image + 9, loader(rig).load("plex_7"))
        val request = rig.requests.single()
        assertEquals("plex-secret", request.headers["X-Plex-Token"])
        assertNull(request.headers[HttpHeaders.Authorization])
        assertFalse(request.url.toString().contains("plex-secret"))
    }

    @Test
    fun `a redirect to another host gets no credential`() = runBlocking {
        val rig = Rig { request ->
            if (request.url.host == "plex.test") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.test/thumb.jpg"))
            else ok(this)
        }
        assertContentEquals(image + 9, loader(rig).load("plex_7"))
        assertEquals(2, rig.requests.size)
        val second = rig.requests[1]
        assertEquals("cdn.test", second.url.host)
        assertNull(second.headers["X-Plex-Token"])
        assertNull(second.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a cover address outside the book's server is never asked for`() = runBlocking {
        val rig = Rig { ok(this) }
        assertNull(loader(rig).load("abs_far"))
        assertTrue(rig.requests.isEmpty())
    }

    @Test
    fun `a book of a removed server, an unknown book or a book without a cover has none`() = runBlocking {
        val rig = Rig { ok(this) }
        val loader = loader(rig, local = mapOf("abs_gone" to image))
        assertNull(loader.load("abs_gone"))
        assertNull(loader.load("abs_unknown"))
        assertNull(loader.load("abs_none"))
        assertNull(loader.load("../etc"))
        assertTrue(rig.requests.isEmpty())
    }

    @Test
    fun `a downloaded cover is used first and needs no network`() = runBlocking {
        val rig = Rig { throw IOException("offline") }
        val bytes = loader(rig, local = mapOf("abs_1" to byteArrayOf(5))).load("abs_1")
        assertContentEquals(byteArrayOf(5, 9), bytes)
        assertTrue(rig.requests.isEmpty())
    }

    @Test
    fun `a failure is no cover, and it is not asked again at once`() = runBlocking {
        val rig = Rig { throw IOException("offline") }
        val loader = loader(rig)
        assertNull(loader.load("abs_1"))
        assertNull(loader.load("abs_1"))
        assertEquals(1, rig.requests.size)
        now += 61_000
        assertNull(loader.load("abs_1"))
        assertEquals(2, rig.requests.size)
    }

    @Test
    fun `an error answer, a refused token or an image that does not decode is no cover`() = runBlocking {
        val authorizer = CountingAuthorizer()
        assertNull(loader(Rig { respond("", HttpStatusCode.Unauthorized) }, authorizer).load("abs_1"))
        // A refused token is not refreshed for a cover: one call, no retry.
        assertEquals(1, authorizer.calls)
        assertNull(loader(Rig { respond("", HttpStatusCode.NotFound) }).load("plex_7"))
        assertNull(loader(Rig { ok(this) }, downscale = { null }).load("abs_1"))
    }

    @Test
    fun `an answer that is too big is no cover`() = runBlocking {
        val big = ByteArray(9 * 1024 * 1024)
        assertNull(loader(Rig { respond(big, HttpStatusCode.OK) }).load("abs_1"))
    }

    @Test
    fun `a server that does not answer in time is no cover`() = runBlocking {
        val rig = Rig { delay(10_000); ok(this) }
        assertNull(loader(rig, timeoutMs = 50).load("abs_1"))
    }

    @Test
    fun `covers are kept in memory, the oldest dropped first`() = runBlocking {
        val rig = Rig { ok(this) }
        val loader = CoverBytesLoader(
            coverOf = { id -> books[id] }, server = { id -> listOf(abs, plex).firstOrNull { it.id == id } },
            authorizer = CountingAuthorizer(), localCover = { null }, downscale = { it }, client = rig.client,
            io = Dispatchers.Unconfined, nowMs = { now }, cacheSize = 2,
        )
        loader.load("abs_1")
        loader.load("abs_1")
        assertEquals(1, rig.requests.size)
        loader.load("abs_2")
        loader.load("plex_7")
        loader.load("abs_2")
        assertEquals(3, rig.requests.size)
        loader.load("abs_1")
        assertEquals(4, rig.requests.size)
    }

    @Test
    fun `the same cover asked for at once is fetched once`() = runBlocking {
        val rig = Rig { delay(50); ok(this) }
        val loader = loader(rig)
        val results = (1..4).map { async(Dispatchers.Default) { loader.load("abs_1") } }.awaitAll()
        assertTrue(results.all { it != null })
        assertEquals(1, rig.requests.size)
    }
}
