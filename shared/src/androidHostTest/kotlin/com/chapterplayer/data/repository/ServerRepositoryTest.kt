package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRepositoryTest {

    private class FakeDao(initial: List<ServerConfigEntity> = emptyList(), var failInsert: Boolean = false) : ServerConfigDao {
        val rows = MutableStateFlow(initial.associateBy { it.id })
        override fun observeAll(): Flow<List<ServerConfigEntity>> = rows.map { it.values.sortedBy { e -> e.name } }
        override suspend fun getAll(): List<ServerConfigEntity> = rows.value.values.toList()
        override suspend fun getById(id: String): ServerConfigEntity? = rows.value[id]
        override fun observeBySource(source: String): Flow<List<ServerConfigEntity>> = rows.map { it.values.filter { e -> e.source == source } }
        override suspend fun insert(config: ServerConfigEntity) { if (failInsert) error("disk full"); rows.value = rows.value + (config.id to config) }
        override suspend fun update(config: ServerConfigEntity) { insert(config) }
        override suspend fun delete(config: ServerConfigEntity) { rows.value = rows.value - config.id }
        override suspend fun deleteById(id: String) { rows.value = rows.value - id }
    }

    private class FakeCredentials(val tokens: MutableMap<String, String> = mutableMapOf(), var failPutAfter: Int = Int.MAX_VALUE, var alwaysFail: Boolean = false) : CredentialStore {
        private var puts = 0
        override suspend fun get(serverId: String) = tokens[serverId]
        override suspend fun put(serverId: String, token: String) {
            if (alwaysFail) error("broken")
            if (puts++ >= failPutAfter) error("interrupted")
            tokens[serverId] = token
        }
        override suspend fun remove(serverId: String) { tokens.remove(serverId) }
    }

    private fun entity(id: String, token: String) = ServerConfigEntity(id, "ABS", "Server $id", "http://s.test", token, null)
    private fun config(id: String, token: String) = ServerConfig(id, ServerSource.ABS, "Server $id", "http://s.test", token, null)

    @Test
    fun `saving keeps the token out of the database`() = runTest {
        val dao = FakeDao(); val creds = FakeCredentials()

        ServerRepositoryImpl(dao, creds).save(config("a", "tok-a"))

        assertEquals("", dao.rows.value.getValue("a").token)
        assertEquals("tok-a", creds.tokens["a"])
    }

    @Test
    fun `reading gives the token back`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeCredentials())
        repo.save(config("a", "tok-a"))

        assertEquals("tok-a", repo.getById("a")?.token)
        assertEquals(listOf("tok-a"), repo.observeAll().first().map { it.token })
    }

    @Test
    fun `deleting a server removes its token`() = runTest {
        val dao = FakeDao(); val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, creds)
        repo.save(config("a", "same")); repo.save(config("b", "same"))

        repo.delete("a")

        assertNull(creds.tokens["a"])
        assertEquals("same", creds.tokens["b"])
        assertNull(repo.getById("a"))
    }

    @Test
    fun `a token the keystore can no longer decrypt reads as empty`() = runTest {
        val dao = FakeDao(listOf(entity("a", ""))) // migrated earlier, then the key was lost
        val repo = ServerRepositoryImpl(dao, FakeCredentials())

        assertEquals("", repo.getById("a")?.token)
    }

    @Test
    fun `tokens already in the database move to the credential store on first use`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a"), entity("b", "legacy-b")))
        val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, creds)

        assertEquals("legacy-a", repo.getById("a")?.token)

        assertEquals(mapOf("a" to "legacy-a", "b" to "legacy-b"), creds.tokens)
        assertEquals(listOf("", ""), dao.rows.value.values.map { it.token })
    }

    @Test
    fun `an interrupted migration finishes next time without losing a token`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a"), entity("b", "legacy-b")))
        val creds = FakeCredentials(failPutAfter = 1) // the second write fails

        runCatching { ServerRepositoryImpl(dao, creds).getById("a") }
        creds.failPutAfter = Int.MAX_VALUE
        val second = ServerRepositoryImpl(dao, creds)

        assertEquals("legacy-b", second.getById("b")?.token)
        assertEquals("legacy-a", second.getById("a")?.token)
        assertEquals(mapOf("a" to "legacy-a", "b" to "legacy-b"), creds.tokens)
        assertEquals(listOf("", ""), dao.rows.value.values.map { it.token })
    }

    @Test
    fun `a token with unusual characters is kept exactly`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeCredentials())
        val odd = "e30=.\"quoted\" 'x' \\ \n tab\t ✓ ${"y".repeat(5000)}"

        repo.save(config("a", odd))

        assertEquals(odd, repo.getById("a")?.token)
    }

    @Test
    fun `a failing credential store does not stop a legacy token being read`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a")))
        val repo = ServerRepositoryImpl(dao, FakeCredentials(alwaysFail = true))

        assertEquals("legacy-a", repo.getById("a")?.token)
        assertEquals(listOf("legacy-a"), repo.observeAll().first().map { it.token })
        assertEquals("legacy-a", dao.rows.value.getValue("a").token)
    }

    @Test
    fun `a migration that failed is retried on the same repository`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a")))
        val creds = FakeCredentials(alwaysFail = true)
        val repo = ServerRepositoryImpl(dao, creds)
        repo.getById("a")

        creds.alwaysFail = false
        assertEquals("legacy-a", repo.getById("a")?.token)

        assertEquals(mapOf("a" to "legacy-a"), creds.tokens)
        assertEquals("", dao.rows.value.getValue("a").token)
    }

    @Test
    fun `deleting a server that still holds a legacy token leaves nothing behind`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a")))
        val creds = FakeCredentials()

        ServerRepositoryImpl(dao, creds).delete("a")

        assertEquals(emptyMap(), dao.rows.value)
        assertEquals(emptyMap(), creds.tokens)
    }

    @Test
    fun `a failed insert for a new server leaves no token behind`() = runTest {
        val dao = FakeDao(failInsert = true); val creds = FakeCredentials()

        assertFailsWith<IllegalStateException> { ServerRepositoryImpl(dao, creds).save(config("a", "tok-a")) }

        assertEquals(emptyMap(), creds.tokens)
    }

    @Test
    fun `a failed insert for an existing server keeps its stored token`() = runTest {
        val dao = FakeDao(listOf(entity("a", "")))
        val creds = FakeCredentials(mutableMapOf("a" to "old"))
        dao.failInsert = true

        assertFailsWith<IllegalStateException> { ServerRepositoryImpl(dao, creds).save(config("a", "new")) }

        assertTrue(creds.tokens.containsKey("a"))
    }
}
