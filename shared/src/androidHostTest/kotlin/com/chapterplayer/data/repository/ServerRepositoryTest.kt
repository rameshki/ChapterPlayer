package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ExcludedLibraryDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.BookmarkEntity
import com.chapterplayer.data.local.entity.HistoryEntity
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.ExcludedLibraryEntity
import com.chapterplayer.data.local.entity.LibraryEntity
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

    private class FakeDao(initial: List<ServerConfigEntity> = emptyList(), var failInsert: Boolean = false, val events: MutableList<String> = mutableListOf()) : ServerConfigDao {
        val rows = MutableStateFlow(initial.associateBy { it.id })
        override fun observeAll(): Flow<List<ServerConfigEntity>> = rows.map { it.values.sortedBy { e -> e.name } }
        override suspend fun getAll(): List<ServerConfigEntity> = rows.value.values.toList()
        override suspend fun getById(id: String): ServerConfigEntity? = rows.value[id]
        override fun observeBySource(source: String): Flow<List<ServerConfigEntity>> = rows.map { it.values.filter { e -> e.source == source } }
        override suspend fun insert(config: ServerConfigEntity) { if (failInsert) error("disk full"); rows.value = rows.value + (config.id to config) }
        override suspend fun update(config: ServerConfigEntity) { insert(config) }
        override suspend fun delete(config: ServerConfigEntity) { rows.value = rows.value - config.id }
        override suspend fun deleteById(id: String) { events += "row"; rows.value = rows.value - id }
    }

    private class FakeExcludedDao(initial: List<ExcludedLibraryEntity> = emptyList()) : ExcludedLibraryDao {
        val rows = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ExcludedLibraryEntity>> = rows
        override suspend fun getAll(): List<ExcludedLibraryEntity> = rows.value
        override suspend fun insertAll(rows: List<ExcludedLibraryEntity>) { this.rows.value = (this.rows.value + rows).distinct() }
        override suspend fun delete(serverId: String, libraryId: String) { rows.value = rows.value.filter { !(it.serverId == serverId && it.libraryId == libraryId) } }
        override suspend fun deleteForServer(serverId: String) { rows.value = rows.value.filter { it.serverId != serverId } }
    }

    private class FakeBookDao(
        initial: List<BookEntity> = emptyList(),
        val events: MutableList<String> = mutableListOf(),
        var fail: Boolean = false
    ) : BookDao {
        val books = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<BookEntity>> = books
        override fun observeBySource(source: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.source == source } }
        override fun observeByServer(serverId: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.serverId == serverId } }
        override suspend fun getById(id: String): BookEntity? = books.value.firstOrNull { it.id == id }
        override suspend fun getByServer(serverId: String): List<BookEntity> = books.value.filter { it.serverId == serverId }
        override suspend fun insertAll(books: List<BookEntity>) { val ids = books.map { it.id }.toSet(); this.books.value = this.books.value.filter { it.id !in ids } + books }
        override suspend fun insert(book: BookEntity) { books.value = books.value + book }
        override suspend fun updateChapters(id: String, json: String) {
            books.value = books.value.map { if (it.id == id) it.copy(chaptersJson = json) else it }
        }
        override suspend fun deleteByServer(serverId: String) {
            if (fail) error("db busy")
            events += "books"
            books.value = books.value.filter { it.serverId != serverId }
        }
        override suspend fun deleteByLibrary(serverId: String, libraryId: String) {
            books.value = books.value.filter { !(it.serverId == serverId && it.libraryId == libraryId) }
        }
        override suspend fun deleteAll() { books.value = emptyList() }
        override suspend fun getLastCacheTime(serverId: String): Long? = null
    }

    private fun book(id: String, serverId: String) =
        BookEntity(id, "T$id", "A", "N", 1L, "[]", "u", null, "ABS", serverId, 0L)

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

        ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).save(config("a", "tok-a"))

        assertEquals("", dao.rows.value.getValue("a").token)
        assertEquals("tok-a", creds.tokens["a"])
    }

    @Test
    fun `reading gives the token back`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeExcludedDao(), FakeCredentials(), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
        repo.save(config("a", "tok-a"))

        assertEquals("tok-a", repo.getById("a")?.token)
        assertEquals(listOf("tok-a"), repo.observeAll().first().map { it.token })
    }

    @Test
    fun `deleting a server removes its token`() = runTest {
        val dao = FakeDao(); val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
        repo.save(config("a", "same")); repo.save(config("b", "same"))

        repo.delete("a")

        assertNull(creds.tokens["a"])
        assertEquals("same", creds.tokens["b"])
        assertNull(repo.getById("a"))
    }

    @Test
    fun `a token the keystore can no longer decrypt reads as empty`() = runTest {
        val dao = FakeDao(listOf(entity("a", ""))) // migrated earlier, then the key was lost
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), FakeCredentials(), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        assertEquals("", repo.getById("a")?.token)
    }

    @Test
    fun `tokens already in the database move to the credential store on first use`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a"), entity("b", "legacy-b")))
        val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        assertEquals("legacy-a", repo.getById("a")?.token)

        assertEquals(mapOf("a" to "legacy-a", "b" to "legacy-b"), creds.tokens)
        assertEquals(listOf("", ""), dao.rows.value.values.map { it.token })
    }

    @Test
    fun `an interrupted migration finishes next time without losing a token`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a"), entity("b", "legacy-b")))
        val creds = FakeCredentials(failPutAfter = 1) // the second write fails

        runCatching { ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).getById("a") }
        creds.failPutAfter = Int.MAX_VALUE
        val second = ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        assertEquals("legacy-b", second.getById("b")?.token)
        assertEquals("legacy-a", second.getById("a")?.token)
        assertEquals(mapOf("a" to "legacy-a", "b" to "legacy-b"), creds.tokens)
        assertEquals(listOf("", ""), dao.rows.value.values.map { it.token })
    }

    @Test
    fun `a token with unusual characters is kept exactly`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeExcludedDao(), FakeCredentials(), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
        val odd = "e30=.\"quoted\" 'x' \\ \n tab\t ✓ ${"y".repeat(5000)}"

        repo.save(config("a", odd))

        assertEquals(odd, repo.getById("a")?.token)
    }

    @Test
    fun `a failing credential store does not stop a legacy token being read`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a")))
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), FakeCredentials(alwaysFail = true), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        assertEquals("legacy-a", repo.getById("a")?.token)
        assertEquals(listOf("legacy-a"), repo.observeAll().first().map { it.token })
        assertEquals("legacy-a", dao.rows.value.getValue("a").token)
    }

    @Test
    fun `a migration that failed is retried on the same repository`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a")))
        val creds = FakeCredentials(alwaysFail = true)
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
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

        ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).delete("a")

        assertEquals(emptyMap(), dao.rows.value)
        assertEquals(emptyMap(), creds.tokens)
    }

    @Test
    fun `a failed insert for a new server leaves no token behind`() = runTest {
        val dao = FakeDao(failInsert = true); val creds = FakeCredentials()

        assertFailsWith<IllegalStateException> { ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).save(config("a", "tok-a")) }

        assertEquals(emptyMap(), creds.tokens)
    }

    @Test
    fun `a failed insert for an existing server keeps its stored token`() = runTest {
        val dao = FakeDao(listOf(entity("a", "")))
        val creds = FakeCredentials(mutableMapOf("a" to "old"))
        dao.failInsert = true

        assertFailsWith<IllegalStateException> { ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).save(config("a", "new")) }

        assertTrue(creds.tokens.containsKey("a"))
    }

    @Test
    fun `saving stores the refresh token under its own key and clears it when it becomes null`() = runTest {
        val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(FakeDao(), FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        repo.save(config("a", "tok").copy(refreshToken = "ref"))
        assertEquals("ref", creds.tokens["a.refresh"])
        assertEquals("tok", creds.tokens["a"])

        repo.save(config("a", "tok"))
        assertNull(creds.tokens["a.refresh"])
        assertEquals("tok", creds.tokens["a"])
    }

    @Test
    fun `reads return the refresh token and the turned-off libraries`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeExcludedDao(), FakeCredentials(), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
        repo.save(config("a", "tok").copy(refreshToken = "ref", excludedLibraryIds = setOf("l1", "l2")))
        repo.save(config("b", "tok-b"))

        val one = repo.getById("a")!!
        assertEquals("ref", one.refreshToken)
        assertEquals(setOf("l1", "l2"), one.excludedLibraryIds)
        val all = repo.observeAll().first().associateBy { it.id }
        assertEquals("ref", all.getValue("a").refreshToken)
        assertEquals(setOf("l1", "l2"), all.getValue("a").excludedLibraryIds)
        assertNull(all.getValue("b").refreshToken)
        assertEquals(emptySet(), all.getValue("b").excludedLibraryIds)
    }

    @Test
    fun `saving replaces the turned-off libraries instead of adding to them`() = runTest {
        val excluded = FakeExcludedDao()
        val repo = ServerRepositoryImpl(FakeDao(), excluded, FakeCredentials(), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
        repo.save(config("a", "t").copy(excludedLibraryIds = setOf("l1", "l2")))
        repo.save(config("b", "t").copy(excludedLibraryIds = setOf("x")))

        repo.save(config("a", "t").copy(excludedLibraryIds = setOf("l3")))

        assertEquals(setOf("l3"), repo.getById("a")?.excludedLibraryIds)
        assertEquals(setOf("x"), repo.getById("b")?.excludedLibraryIds)
        assertEquals(2, excluded.rows.value.size)
    }

    @Test
    fun `deleting removes both credential entries and the turned-off libraries`() = runTest {
        val excluded = FakeExcludedDao(); val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(FakeDao(), excluded, creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())
        repo.save(config("a", "t").copy(refreshToken = "r", excludedLibraryIds = setOf("l1")))
        repo.save(config("b", "t2").copy(refreshToken = "r2", excludedLibraryIds = setOf("l2")))

        repo.delete("a")

        assertEquals(mapOf("b" to "t2", "b.refresh" to "r2"), creds.tokens)
        assertEquals(listOf(ExcludedLibraryEntity("b", "l2")), excluded.rows.value)
    }

    @Test
    fun `a missing refresh entry reads as null`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(listOf(entity("a", ""))), FakeExcludedDao(), FakeCredentials(mutableMapOf("a" to "tok")), FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        assertNull(repo.getById("a")?.refreshToken)
        assertEquals("tok", repo.getById("a")?.token)
    }

    @Test
    fun `a failed insert for a new server leaves no refresh token behind`() = runTest {
        val dao = FakeDao(failInsert = true); val creds = FakeCredentials()

        assertFailsWith<IllegalStateException> {
            ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).save(config("a", "t").copy(refreshToken = "r"))
        }

        assertEquals(emptyMap(), creds.tokens)
    }

    @Test
    fun `a legacy token still in the database survives the version 2 schema change`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a")))
        val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), creds, FakeBookDao(), InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        val read = repo.getById("a")

        assertEquals("legacy-a", read?.token)
        assertNull(read?.refreshToken)
        assertEquals("legacy-a", creds.tokens["a"])
        assertEquals("", dao.rows.value.getValue("a").token)
    }

    @Test
    fun `a server without a readable token needs sign in`() {
        assertTrue(config("a", "").needsSignIn)
        assertTrue(!config("a", "t").needsSignIn)
    }

    @Test
    fun `deleting a server removes its books and keeps other servers' books`() = runTest {
        val events = mutableListOf<String>()
        val dao = FakeDao(listOf(entity("a", ""), entity("b", "")), events = events)
        val books = FakeBookDao(listOf(book("1", "a"), book("2", "a"), book("3", "b")), events)
        val creds = FakeCredentials(mutableMapOf("a" to "ta", "a.refresh" to "ra", "b" to "tb"))

        ServerRepositoryImpl(dao, FakeExcludedDao(), creds, books, InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao()).delete("a")

        assertEquals(listOf("3"), books.books.value.map { it.id })
        assertEquals(setOf("b"), dao.rows.value.keys)
        assertEquals(mapOf("b" to "tb"), creds.tokens)
        assertEquals(listOf("books", "row"), events)
    }

    @Test
    fun `deleting a server also removes its libraries and collections but not another server's`() = runTest {
        val dao = FakeDao(listOf(entity("a", ""), entity("b", "")))
        val libraries = InMemoryLibraryDao(listOf(LibraryEntity("a", "l1", "One"), LibraryEntity("b", "l1", "One")))
        val collections = InMemoryCollectionDao(
            listOf(CollectionEntity("a", "c1", "C"), CollectionEntity("b", "c1", "C")),
            listOf(CollectionBookEntity("a", "c1", "x", 0), CollectionBookEntity("b", "c1", "y", 0))
        )
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), FakeCredentials(), FakeBookDao(), libraries, collections, InMemoryBookmarkDao(), InMemoryHistoryDao())

        repo.delete("a")

        assertEquals(listOf("b"), libraries.rows.value.map { it.serverId })
        assertEquals(listOf("b"), collections.collections.value.map { it.serverId })
        assertEquals(listOf("b"), collections.books.value.map { it.serverId })
        assertNull(dao.rows.value["a"])
        assertEquals("b", dao.rows.value.getValue("b").id)
    }

    @Test
    fun `deleting a server also removes its bookmarks and the history of its books but not another server's`() = runTest {
        val dao = FakeDao(listOf(entity("a", ""), entity("b", "")))
        val books = FakeBookDao(listOf(book("1", "a"), book("2", "a"), book("3", "b")))
        val bookmarks = InMemoryBookmarkDao(
            listOf(
                BookmarkEntity.fromDomain(Bookmark("1", 5, "x", 1, BookmarkState.SYNCED), "a"),
                BookmarkEntity.fromDomain(Bookmark("3", 5, "y", 1, BookmarkState.PENDING_CREATE), "b"),
            )
        )
        val history = InMemoryHistoryDao(
            listOf(HistoryEntity(0, "1", 100, 1), HistoryEntity(0, "2", 200, 2), HistoryEntity(0, "3", 300, 3))
        )
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), FakeCredentials(), books, InMemoryLibraryDao(), InMemoryCollectionDao(), bookmarks, history)

        repo.delete("a")

        assertEquals(listOf("b"), bookmarks.rows.value.map { it.serverId })
        assertEquals(listOf("3"), history.rows.value.map { it.bookId })
    }

    @Test
    fun `a failed book removal keeps the server row so the user can delete again`() = runTest {
        val dao = FakeDao(listOf(entity("a", "")))
        val books = FakeBookDao(listOf(book("1", "a")), fail = true)
        val repo = ServerRepositoryImpl(dao, FakeExcludedDao(), FakeCredentials(), books, InMemoryLibraryDao(), InMemoryCollectionDao(), InMemoryBookmarkDao(), InMemoryHistoryDao())

        assertFailsWith<IllegalStateException> { repo.delete("a") }

        assertEquals(setOf("a"), dao.rows.value.keys)
        books.fail = false
        repo.delete("a")
        assertEquals(emptySet(), dao.rows.value.keys)
    }
}
