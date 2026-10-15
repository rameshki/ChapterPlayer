package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbsSessionManagerTest {

    private val now = 1_000_000L

    private fun jwt(exp: Long?): String {
        val enc = { s: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(s.encodeToByteArray()) }
        val payload = if (exp == null) """{"keyId":"k"}""" else """{"userId":"u","exp":$exp}"""
        return enc("""{"alg":"HS256","typ":"JWT"}""") + "." + enc(payload) + ".sig"
    }

    private class FakeServers(initial: ServerConfig) : ServerRepository {
        val holder = MutableStateFlow(initial)
        val saves = CopyOnWriteArrayList<ServerConfig>()
        override fun observeAll(): Flow<List<ServerConfig>> = holder.map { listOf(it) }
        override suspend fun getById(id: String): ServerConfig? = holder.value.takeIf { it.id == id }
        override suspend fun save(config: ServerConfig) {
            saves += config
            holder.value = config
        }
        override suspend fun delete(id: String) {}
    }

    private fun serverWith(token: String, refreshToken: String? = "refresh-old") = ServerConfig(
        id = "srv1", source = ServerSource.ABS, name = "Home", baseUrl = "http://abs.test",
        token = token, refreshToken = refreshToken, excludedLibraryIds = setOf("lib_off")
    )

    private inner class Rig(
        val server: ServerConfig,
        val accepted: Set<String>,
        val refreshBody: String,
        val refreshStatus: HttpStatusCode,
        val refreshThrows: Boolean,
        val refreshDelayMs: Long,
    ) {
        val refreshCount = AtomicInteger()
        val protectedCount = AtomicInteger()
        val refreshHeaders = CopyOnWriteArrayList<String?>()
        val protectedBearers = CopyOnWriteArrayList<String?>()
        val servers = FakeServers(server)
        private val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                refreshCount.incrementAndGet()
                refreshHeaders += request.headers["x-refresh-token"]
                if (refreshDelayMs > 0) delay(refreshDelayMs)
                if (refreshThrows) throw ConnectException("refused")
                respond(refreshBody, refreshStatus, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                protectedCount.incrementAndGet()
                val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                protectedBearers += bearer
                if (bearer in accepted) {
                    respond("""{"libraries":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond("no", HttpStatusCode.Unauthorized)
                }
            }
        }
        val api = AbsApiService(HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) })
        val manager = AbsSessionManager(api, servers) { now }

        /** Runs a protected request with the bearer the manager hands over, returning that bearer. */
        suspend fun use(from: ServerConfig = server): String = manager.authorized(from) { bearer ->
            api.getLibraries(from.baseUrl, bearer)
            bearer
        }
    }

    private val freshAnswer get() =
        """{"user":{"id":"u","username":"x","accessToken":"${jwt(now + 7200)}","refreshToken":"refresh-new"}}"""

    private fun rig(
        server: ServerConfig,
        accepted: Set<String> = emptySet(),
        refreshBody: String = freshAnswer,
        refreshStatus: HttpStatusCode = HttpStatusCode.OK,
        refreshThrows: Boolean = false,
        refreshDelayMs: Long = 0,
    ) = Rig(server, accepted, refreshBody, refreshStatus, refreshThrows, refreshDelayMs)

    @Test
    fun `a fresh token is used as is`() = runTest {
        val token = jwt(now + 3600)
        val r = rig(serverWith(token), accepted = setOf(token))
        assertEquals("Bearer $token", r.use())
        assertEquals(0, r.refreshCount.get())
        assertTrue(r.servers.saves.isEmpty())
    }

    @Test
    fun `a token expiring in 30 seconds is refreshed before the call and saved`() = runTest {
        val newToken = jwt(now + 7200)
        val r = rig(serverWith(jwt(now + 30)), accepted = setOf(newToken))
        assertEquals("Bearer $newToken", r.use())
        assertEquals(1, r.refreshCount.get())
        assertEquals(1, r.protectedCount.get())
        val saved = r.servers.saves.single()
        assertEquals(newToken, saved.token)
        assertEquals("refresh-new", saved.refreshToken)
        assertEquals(setOf("lib_off"), saved.excludedLibraryIds)
        assertEquals("Home", saved.name)
    }

    @Test
    fun `an expired token is refreshed`() = runTest {
        val newToken = jwt(now + 7200)
        val r = rig(serverWith(jwt(now - 10)), accepted = setOf(newToken))
        assertEquals("Bearer $newToken", r.use())
        assertEquals(1, r.refreshCount.get())
    }

    @Test
    fun `a 401 triggers one refresh and one retry that succeeds`() = runTest {
        val newToken = jwt(now + 7200)
        val r = rig(serverWith(jwt(now + 3600)), accepted = setOf(newToken))
        assertEquals("Bearer $newToken", r.use())
        assertEquals(1, r.refreshCount.get())
        assertEquals(2, r.protectedCount.get())
        assertEquals(newToken, r.servers.saves.single().token)
    }

    @Test
    fun `a second 401 after the refresh is sign-in required and does not refresh again`() = runTest {
        val r = rig(serverWith(jwt(now + 3600)), accepted = emptySet())
        val e = assertFailsWith<SignInRequiredException> { r.use() }
        assertEquals("Home", e.serverName)
        assertEquals(1, r.refreshCount.get())
        assertEquals(2, r.protectedCount.get())
    }

    @Test
    fun `a refresh answered 401 is sign-in required`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshStatus = HttpStatusCode.Unauthorized, refreshBody = "no")
        assertFailsWith<SignInRequiredException> { r.use() }
        assertEquals(0, r.protectedCount.get())
    }

    @Test
    fun `a refresh answered 403 is sign-in required`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshStatus = HttpStatusCode.Forbidden, refreshBody = "no")
        assertFailsWith<SignInRequiredException> { r.use() }
    }

    @Test
    fun `a refresh failing with a connection error is rethrown unchanged`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshThrows = true)
        val e = assertFailsWith<IOException> { r.use() }
        assertTrue(e is ConnectException, "was $e")
    }

    @Test
    fun `a refresh answered 500 is rethrown and not sign-in required`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshStatus = HttpStatusCode.InternalServerError, refreshBody = "oops")
        val e = assertFailsWith<com.chapterplayer.data.remote.HttpStatusException> { r.use() }
        assertEquals(500, e.status.value)
    }

    @Test
    fun `a refresh answer that cannot be decoded gives a message with no response text`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshBody = "{secret-token-text <not json")
        val e = assertFailsWith<IOException> { r.use() }
        assertEquals("The server sent an unexpected answer", e.message)
        assertFalse("secret-token-text" in e.toString())
        // Coroutine stack-trace recovery may attach the original as cause; it carries the same fixed text.
        assertTrue(generateSequence<Throwable>(e) { it.cause }.all { it.message == "The server sent an unexpected answer" })
    }

    @Test
    fun `a refresh answer with no token at all is sign-in required`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshBody = """{"user":{"id":"u","username":"x"}}""")
        assertFailsWith<SignInRequiredException> { r.use() }
    }

    @Test
    fun `no refresh token plus a 401 is sign-in required without a refresh`() = runTest {
        val r = rig(serverWith(jwt(now + 3600), refreshToken = null), accepted = emptySet())
        assertFailsWith<SignInRequiredException> { r.use() }
        assertEquals(0, r.refreshCount.get())
        assertEquals(1, r.protectedCount.get())
    }

    @Test
    fun `an expiring token with no refresh token is used as is`() = runTest {
        val token = jwt(now + 5)
        val r = rig(serverWith(token, refreshToken = null), accepted = setOf(token))
        assertEquals("Bearer $token", r.use())
        assertEquals(0, r.refreshCount.get())
    }

    @Test
    fun `a blank token is sign-in required without any request`() = runTest {
        val r = rig(serverWith(""))
        assertFailsWith<SignInRequiredException> { r.use() }
        assertEquals(0, r.refreshCount.get())
        assertEquals(0, r.protectedCount.get())
    }

    @Test
    fun `an API key with no exp is used as is`() = runTest {
        val key = jwt(null)
        val r = rig(serverWith(key), accepted = setOf(key))
        assertEquals("Bearer $key", r.use())
        assertEquals(0, r.refreshCount.get())
    }

    @Test
    fun `ten concurrent calls with an expired token make exactly one refresh request`() = runBlocking {
        val newToken = jwt(now + 7200)
        val r = rig(serverWith(jwt(now - 10)), accepted = setOf(newToken), refreshDelayMs = 200)
        val results = withContext(Dispatchers.Default) {
            (1..10).map { async { r.use() } }.awaitAll()
        }
        assertEquals(1, r.refreshCount.get())
        assertTrue(results.all { it == "Bearer $newToken" })
        assertEquals(1, r.servers.saves.size)
    }

    @Test
    fun `an answer without a new refresh token keeps the old one`() = runTest {
        val newToken = jwt(now + 7200)
        val r = rig(
            serverWith(jwt(now - 10)),
            accepted = setOf(newToken),
            refreshBody = """{"user":{"id":"u","username":"x","accessToken":"$newToken"}}"""
        )
        r.use()
        assertEquals("refresh-old", r.servers.saves.single().refreshToken)
        assertEquals(newToken, r.servers.saves.single().token)
    }

    @Test
    fun `an answer with only the legacy token saves that as the token`() = runTest {
        val r = rig(
            serverWith(jwt(now - 10)),
            accepted = setOf("legacy-made-up"),
            refreshBody = """{"user":{"id":"u","username":"x","token":"legacy-made-up"}}"""
        )
        assertEquals("Bearer legacy-made-up", r.use())
        assertEquals("legacy-made-up", r.servers.saves.single().token)
    }

    @Test
    fun `a stale server argument does not overwrite newer stored data`() = runTest {
        val newToken = jwt(now + 7200)
        val stale = serverWith(jwt(now - 10))
        val r = rig(stale, accepted = setOf(newToken))
        r.servers.holder.value = stale.copy(excludedLibraryIds = setOf("lib_off", "lib_two"))
        r.use(from = stale)
        assertEquals(setOf("lib_off", "lib_two"), r.servers.saves.single().excludedLibraryIds)
    }

    @Test
    fun `cancelling the caller after the refresh arrived still saves the new tokens`() = runBlocking {
        val newToken = jwt(now + 7200)
        val r = rig(serverWith(jwt(now - 10)), accepted = setOf(newToken), refreshDelayMs = 200)
        val job = launch(Dispatchers.Default) { r.use() }
        withTimeout(5_000) { while (r.refreshCount.get() == 0) delay(5) }
        job.cancel()
        job.join()
        assertEquals(newToken, r.servers.saves.single().token)
        assertEquals("refresh-new", r.servers.saves.single().refreshToken)
    }

    @Test
    fun `an ahead-of-expiry refresh that cannot connect continues with the current token`() = runTest {
        val token = jwt(now + 30)
        val r = rig(serverWith(token), accepted = setOf(token), refreshThrows = true)
        assertEquals("Bearer $token", r.use())
        assertTrue(r.servers.saves.isEmpty())
    }

    @Test
    fun `a refresh error on an already expired token is rethrown`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshStatus = HttpStatusCode.InternalServerError, refreshBody = "oops")
        val e = assertFailsWith<com.chapterplayer.data.remote.HttpStatusException> { r.use() }
        assertEquals(500, e.status.value)
        assertEquals(0, r.protectedCount.get())
    }

    @Test
    fun `the refresh uses the freshly read refresh token, not the argument`() = runTest {
        val token = jwt(now - 10)
        val arg = serverWith(token, refreshToken = "refresh-old")
        val r = rig(arg, accepted = setOf(jwt(now + 7200)))
        r.servers.holder.value = arg.copy(refreshToken = "refresh-newer")
        r.use(from = arg)
        assertEquals(listOf<String?>("refresh-newer"), r.refreshHeaders.toList())
    }

    @Test
    fun `a stale expired argument with a fresh stored token makes no refresh request`() = runTest {
        val fresh = jwt(now + 3600)
        val arg = serverWith(jwt(now - 10))
        val r = rig(arg, accepted = setOf(fresh))
        r.servers.holder.value = arg.copy(token = fresh)
        assertEquals("Bearer $fresh", r.use(from = arg))
        assertEquals(0, r.refreshCount.get())
        assertEquals(listOf<String?>(fresh), r.protectedBearers.toList())
    }

    @Test
    fun `a refused refresh marks the stored server as needing sign-in and keeps the rest`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshStatus = HttpStatusCode.Unauthorized, refreshBody = "no")
        assertFailsWith<SignInRequiredException> { r.use() }
        val stored = r.servers.holder.value
        assertTrue(stored.needsSignIn)
        assertEquals(null, stored.refreshToken)
        assertEquals("Home", stored.name)
        assertEquals("http://abs.test", stored.baseUrl)
        assertEquals(setOf("lib_off"), stored.excludedLibraryIds)
    }

    @Test
    fun `a refresh answer with no token marks the server as needing sign-in`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshBody = """{"user":{"id":"u","username":"x"}}""")
        assertFailsWith<SignInRequiredException> { r.use() }
        assertTrue(r.servers.holder.value.needsSignIn)
    }

    @Test
    fun `a later call after a refused refresh fails without any request`() = runTest {
        val r = rig(serverWith(jwt(now - 10)), refreshStatus = HttpStatusCode.Forbidden, refreshBody = "no")
        assertFailsWith<SignInRequiredException> { r.use() }
        val reread = r.servers.holder.value
        assertFailsWith<SignInRequiredException> { r.use(from = reread) }
        assertEquals(1, r.refreshCount.get())
        assertEquals(0, r.protectedCount.get())
    }

    @Test
    fun `a refresh answered 500 leaves the tokens untouched`() = runTest {
        val start = serverWith(jwt(now - 10))
        val r = rig(start, refreshStatus = HttpStatusCode.InternalServerError, refreshBody = "oops")
        assertFailsWith<com.chapterplayer.data.remote.HttpStatusException> { r.use() }
        assertTrue(r.servers.saves.isEmpty())
        assertEquals(start, r.servers.holder.value)
    }

    @Test
    fun `a refresh connect error leaves the tokens untouched`() = runTest {
        val start = serverWith(jwt(now - 10))
        val r = rig(start, refreshThrows = true)
        assertFailsWith<IOException> { r.use() }
        assertTrue(r.servers.saves.isEmpty())
        assertEquals(start, r.servers.holder.value)
    }

    @Test
    fun `an undecodable refresh answer leaves the tokens untouched`() = runTest {
        val start = serverWith(jwt(now - 10))
        val r = rig(start, refreshBody = "{not json")
        assertFailsWith<IOException> { r.use() }
        assertTrue(r.servers.saves.isEmpty())
    }

    @Test
    fun `no refresh token plus a 401 marks the server as needing sign-in`() = runTest {
        val r = rig(serverWith(jwt(now + 3600), refreshToken = null), accepted = emptySet())
        assertFailsWith<SignInRequiredException> { r.use() }
        val stored = r.servers.holder.value
        assertTrue(stored.needsSignIn)
        assertEquals(setOf("lib_off"), stored.excludedLibraryIds)
        assertEquals("Home", stored.name)
    }

    @Test
    fun `a refused refresh ahead of expiry marks the server as needing sign-in and makes no call`() = runTest {
        val r = rig(serverWith(jwt(now + 30)), refreshStatus = HttpStatusCode.Unauthorized, refreshBody = "no")
        assertFailsWith<SignInRequiredException> { r.use() }
        assertEquals(0, r.protectedCount.get())
        assertTrue(r.servers.holder.value.needsSignIn)
        assertEquals(setOf("lib_off"), r.servers.holder.value.excludedLibraryIds)
    }

    @Test
    fun `a late 401 for an old token does not blank tokens from a newer sign-in`() = runTest {
        val old = jwt(now + 3600)
        val newer = jwt(now + 3601)
        val r = rig(serverWith(old, refreshToken = null), accepted = emptySet())
        // The user signed in again while the request with the old token was still in flight.
        r.servers.holder.value = serverWith(newer, refreshToken = "refresh-newer")
        assertFailsWith<SignInRequiredException> { r.use(from = serverWith(old, refreshToken = null)) }
        val stored = r.servers.holder.value
        assertEquals(newer, stored.token)
        assertEquals("refresh-newer", stored.refreshToken)
    }
}
