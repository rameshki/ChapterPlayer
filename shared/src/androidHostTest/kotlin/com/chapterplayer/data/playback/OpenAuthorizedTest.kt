package com.chapterplayer.data.playback

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.repository.ServerRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OpenAuthorizedTest {

    private class Unauthorized : Exception("401")

    private class FakeServers(initial: List<ServerConfig>) : ServerRepository {
        val holder = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ServerConfig>> = holder
        override suspend fun getById(id: String) = holder.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {
            holder.value = holder.value.map { if (it.id == config.id) config else it }
        }
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    private fun server(id: String, base: String, source: ServerSource = ServerSource.ABS, token: String = "old") = ServerConfig(
        id = id, source = source, name = id, baseUrl = base, token = token, refreshToken = "refresh-old",
    )

    private fun manager(servers: FakeServers, refreshCount: IntArray): AbsSessionManager {
        val engine = MockEngine {
            refreshCount[0]++
            respond(
                """{"user":{"id":"u","username":"x","accessToken":"newtoken","refreshToken":"refresh-new"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = AbsApiService(HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) })
        return AbsSessionManager(api, servers) { 1_000_000L }
    }

    private val url = "http://abs.test/api/items/1/file/2"

    private fun run(
        servers: FakeServers,
        accepted: Set<String?>,
        attempts: MutableList<String?>,
        refreshes: IntArray = IntArray(1),
        failWith: Exception? = null,
        headers: MutableList<String?> = mutableListOf(),
    ) = openAuthorized(url, servers, manager(servers, refreshes), { it is Unauthorized }) { header ->
        headers += header?.name
        val authorization = header?.value
        attempts += authorization
        failWith?.let { throw it }
        if (authorization !in accepted) throw Unauthorized()
        "opened"
    }

    @Test
    fun `a 401 refreshes once and retries once with the new token`() {
        val attempts = mutableListOf<String?>()
        val refreshes = IntArray(1)
        val result = run(FakeServers(listOf(server("s", "http://abs.test"))), setOf("Bearer newtoken"), attempts, refreshes)
        assertEquals("opened", result)
        assertEquals(listOf<String?>("Bearer old", "Bearer newtoken"), attempts)
        assertEquals(1, refreshes[0])
    }

    @Test
    fun `a second 401 means sign in again and stops`() {
        val attempts = mutableListOf<String?>()
        assertFailsWith<SignInRequiredException> {
            run(FakeServers(listOf(server("s", "http://abs.test"))), emptySet(), attempts)
        }
        assertEquals(2, attempts.size)
    }

    @Test
    fun `other errors pass through without a retry`() {
        val attempts = mutableListOf<String?>()
        val refreshes = IntArray(1)
        assertFailsWith<IOException> {
            run(FakeServers(listOf(server("s", "http://abs.test"))), emptySet(), attempts, refreshes, IOException("boom"))
        }
        assertEquals(1, attempts.size)
        assertEquals(0, refreshes[0])
    }

    @Test
    fun `an address of no configured server gets no header and no refresh`() {
        val attempts = mutableListOf<String?>()
        val refreshes = IntArray(1)
        val result = run(FakeServers(listOf(server("s", "http://other.test"))), setOf(null), attempts, refreshes)
        assertEquals("opened", result)
        assertEquals(listOf<String?>(null), attempts)
        assertEquals(0, refreshes[0])
    }

    @Test
    fun `a plex server gets its token in the X-Plex-Token header, never a bearer, and no refresh`() {
        val attempts = mutableListOf<String?>()
        val headers = mutableListOf<String?>()
        val refreshes = IntArray(1)
        run(FakeServers(listOf(server("p", "http://abs.test", ServerSource.PLEX, token = "plextok"))), setOf("plextok"), attempts, refreshes, headers = headers)
        assertEquals(listOf<String?>("plextok"), attempts)
        assertEquals(listOf<String?>("X-Plex-Token"), headers)
        assertEquals(0, refreshes[0])
    }

    @Test
    fun `a 401 from a plex server means sign in again, without a retry`() {
        val attempts = mutableListOf<String?>()
        val refreshes = IntArray(1)
        assertFailsWith<SignInRequiredException> {
            run(FakeServers(listOf(server("p", "http://abs.test", ServerSource.PLEX, token = "plextok"))), emptySet(), attempts, refreshes)
        }
        assertEquals(1, attempts.size)
        assertEquals(0, refreshes[0])
    }

    @Test
    fun `a plex token goes only to its own server`() {
        val attempts = mutableListOf<String?>()
        run(FakeServers(listOf(server("p", "http://plex.test:32400", ServerSource.PLEX, token = "plextok"))), setOf(null), attempts)
        assertNull(attempts.single())
    }

    @Test
    fun `the header goes only to the server the address belongs to, with that server's token`() {
        val attempts = mutableListOf<String?>()
        val two = FakeServers(
            listOf(server("a", "http://other.test", token = "tokA"), server("b", "http://abs.test", token = "tokB"))
        )
        run(two, setOf("Bearer tokB"), attempts)
        assertEquals(listOf<String?>("Bearer tokB"), attempts)
    }
}
