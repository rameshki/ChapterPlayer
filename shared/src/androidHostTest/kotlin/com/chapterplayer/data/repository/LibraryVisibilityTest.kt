package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ExcludedLibraryDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ExcludedLibraryEntity
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.LibraryToggle
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import com.chapterplayer.data.model.ServerLibraries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Turning libraries on and off, and the list the Libraries check-list reads. */
class LibraryVisibilityTest {

    // Shared by all fakes below. rig() clears it after seeding, so a test sees only the calls it triggers.
    private val events = mutableListOf<String>()

    private inner class FakeServerDao : ServerConfigDao {
        val rows = MutableStateFlow(emptyMap<String, ServerConfigEntity>())
        override fun observeAll(): Flow<List<ServerConfigEntity>> = rows.map { it.values.sortedBy { e -> e.name } }
        override suspend fun getAll(): List<ServerConfigEntity> = rows.value.values.toList()
        override suspend fun getById(id: String): ServerConfigEntity? = rows.value[id]
        override fun observeBySource(source: String): Flow<List<ServerConfigEntity>> = rows.map { it.values.filter { e -> e.source == source } }
        override suspend fun insert(config: ServerConfigEntity) { events += "server-row"; rows.value = rows.value + (config.id to config) }
        override suspend fun update(config: ServerConfigEntity) { insert(config) }
        override suspend fun delete(config: ServerConfigEntity) { rows.value = rows.value - config.id }
        override suspend fun deleteById(id: String) { rows.value = rows.value - id }
    }

    private inner class FakeExcludedDao(initial: List<ExcludedLibraryEntity> = emptyList()) : ExcludedLibraryDao {
        val rows = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<ExcludedLibraryEntity>> = rows
        override suspend fun getAll(): List<ExcludedLibraryEntity> = rows.value
        // Primary key (serverId, libraryId) with REPLACE: a repeated row stays one row.
        override suspend fun insertAll(rows: List<ExcludedLibraryEntity>) { events += "exclude"; this.rows.value = (this.rows.value + rows).distinct() }
        override suspend fun delete(serverId: String, libraryId: String) { events += "include"; rows.value = rows.value.filter { !(it.serverId == serverId && it.libraryId == libraryId) } }
        override suspend fun deleteForServer(serverId: String) { rows.value = rows.value.filter { it.serverId != serverId } }
    }

    private inner class FakeBookDao(initial: List<BookEntity>) : BookDao {
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
        override suspend fun deleteByServer(serverId: String) { books.value = books.value.filter { it.serverId != serverId } }
        override suspend fun deleteByLibrary(serverId: String, libraryId: String) {
            events += "books"
            books.value = books.value.filter { !(it.serverId == serverId && it.libraryId == libraryId) }
        }
        override suspend fun deleteAll() { books.value = emptyList() }
        override suspend fun getLastCacheTime(serverId: String): Long? = null
    }

    private class FakeCredentials(val tokens: MutableMap<String, String> = mutableMapOf()) : CredentialStore {
        override suspend fun get(serverId: String) = tokens[serverId]
        override suspend fun put(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun remove(serverId: String) { tokens.remove(serverId) }
    }

    private fun book(id: String, serverId: String, libraryId: String) =
        BookEntity(id, "T$id", "A", "N", 1L, "[]", "u", null, "ABS", serverId, 0L, libraryId = libraryId)

    private fun config(id: String, name: String = "Server $id") =
        ServerConfig(id, ServerSource.ABS, name, "http://s.test", "tok-$id", null, "refresh-$id")

    private inner class Rig {
        val creds = FakeCredentials()
        val excludedDao = FakeExcludedDao()
        val bookDao = FakeBookDao(
            listOf(book("a1", "a", "lib1"), book("a2", "a", "lib1"), book("a3", "a", "lib2"), book("b1", "b", "lib1"))
        )
        val repo = ServerRepositoryImpl(FakeServerDao(), excludedDao, creds, bookDao, InMemoryLibraryDao(), InMemoryCollectionDao())
        fun bookIds() = bookDao.books.value.map { it.id }
    }

    private suspend fun rig(excluded: List<ExcludedLibraryEntity> = emptyList()): Rig {
        val r = Rig()
        r.repo.save(config("a")); r.repo.save(config("b"))
        r.excludedDao.rows.value = excluded // saving replaces the turned-off list, so seed it afterwards
        events.clear()
        return r
    }

    @Test
    fun `turning a library off adds the exclusion and removes only that library books`() = runTest {
        val r = rig()

        r.repo.setLibraryEnabled("a", "lib1", false)

        assertEquals(setOf("lib1"), r.repo.getById("a")?.excludedLibraryIds)
        assertEquals(listOf("a3", "b1"), r.bookIds())
        assertEquals(emptySet(), r.repo.getById("b")?.excludedLibraryIds)
    }

    @Test
    fun `turning a library off adds the exclusion before it removes the books`() = runTest {
        val r = rig()

        r.repo.setLibraryEnabled("a", "lib1", false)

        assertEquals(listOf("exclude", "books"), events)
    }

    @Test
    fun `turning a library on removes the exclusion and touches no books`() = runTest {
        val r = rig(listOf(ExcludedLibraryEntity("a", "lib1"), ExcludedLibraryEntity("a", "lib2")))
        val before = r.bookIds()

        r.repo.setLibraryEnabled("a", "lib1", true)

        assertEquals(setOf("lib2"), r.repo.getById("a")?.excludedLibraryIds)
        assertEquals(before, r.bookIds())
        assertEquals(listOf("include"), events)
    }

    @Test
    fun `turning libraries on or off leaves tokens and the server row alone`() = runTest {
        val r = rig()
        val before = r.repo.getById("a")!!

        r.repo.setLibraryEnabled("a", "lib1", false)
        r.repo.setLibraryEnabled("a", "lib2", false)
        r.repo.setLibraryEnabled("a", "lib1", true)

        val after = r.repo.getById("a")!!
        assertEquals("tok-a", after.token)
        assertEquals("refresh-a", after.refreshToken)
        assertEquals(before.copy(excludedLibraryIds = setOf("lib2")), after)
        assertEquals(mapOf("a" to "tok-a", "b" to "tok-b", "a.refresh" to "refresh-a", "b.refresh" to "refresh-b"), r.creds.tokens)
        assertTrue("server-row" !in events)
    }

    @Test
    fun `an unknown server id does nothing`() = runTest {
        val r = rig()

        r.repo.setLibraryEnabled("nope", "lib1", false)
        r.repo.setLibraryEnabled("nope", "lib1", true)

        assertTrue(r.excludedDao.getAll().isEmpty())
        assertEquals(4, r.bookIds().size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `turning the same library off twice is harmless`() = runTest {
        val r = rig()

        r.repo.setLibraryEnabled("a", "lib1", false)
        r.repo.setLibraryEnabled("a", "lib1", false)

        assertEquals(setOf("lib1"), r.repo.getById("a")?.excludedLibraryIds)
        assertEquals(listOf("a3", "b1"), r.bookIds())
    }

    @Test
    fun `turning off every library keeps the server listed`() = runTest {
        val r = rig()

        r.repo.setLibraryEnabled("a", "lib1", false)
        r.repo.setLibraryEnabled("a", "lib2", false)

        assertEquals(listOf("a", "b"), r.repo.observeAll().first().map { it.id })
        assertEquals(listOf("b1"), r.bookIds())
    }

    // ---- the list the check-list reads ----

    private class FakeServers(servers: List<ServerConfig>) : ServerRepository {
        val state = MutableStateFlow(servers)
        override fun observeAll(): Flow<List<ServerConfig>> = state
        override suspend fun getById(id: String): ServerConfig? = state.value.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    private class FakeBooks(libraries: List<LibraryInfo>) : BookRepository {
        val libs = MutableStateFlow(libraries)
        override fun observeLibraries(): Flow<List<LibraryInfo>> = libs
        override fun observeAllBooks() = error("unused")
        override fun observeBooksByServer(serverId: String) = error("unused")
        override fun observeCollections() = error("unused")
        override suspend fun syncBooksFromServer(server: ServerConfig) = error("unused")
        override suspend fun loadDetails(bookId: String) = error("unused")
        override suspend fun startPlayback(bookId: String) = error("unused")
        override suspend fun closePlayback(session: com.chapterplayer.data.playback.PlaybackSession, positionMs: Long, listenedMs: Long) = error("unused")
        override suspend fun syncPlayback(session: com.chapterplayer.data.playback.PlaybackSession, positionMs: Long, listenedMs: Long) = error("unused")
        override suspend fun fetchServerProgress(bookId: String) = error("unused")
        override suspend fun queueProgress(pending: com.chapterplayer.domain.playback.PendingProgress) = error("unused")
        override suspend fun pendingProgress() = error("unused")
        override suspend fun deletePending(bookId: String, ifUpdatedAtMs: Long?) = error("unused")
        override suspend fun markPendingAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long?) = error("unused")
        override suspend fun pushProgress(pending: com.chapterplayer.domain.playback.PendingProgress) = error("unused")
        override suspend fun saveServerProgress(bookId: String, server: com.chapterplayer.domain.playback.ServerProgress) = error("unused")
        override suspend fun mergeAllServerProgress(serverId: String) = error("unused")
        override fun observeProgress(bookId: String) = error("unused")
        override fun observeInProgressBooks() = error("unused")
        override fun observeAllProgress() = error("unused")
        override suspend fun saveProgress(progress: ReadingProgress) = error("unused")
    }

    @Test
    fun `the library list shows turned-off libraries as not enabled`() = runTest {
        val catalog = LibraryCatalogImpl(
            FakeServers(listOf(config("a").copy(excludedLibraryIds = setOf("l2")))),
            FakeBooks(listOf(LibraryInfo("a", "l1", "Books"), LibraryInfo("a", "l2", "Kids")))
        )

        val list = catalog.observeServerLibraries().first()

        assertEquals(listOf(LibraryToggle("l1", "Books", true), LibraryToggle("l2", "Kids", false)), list.single().libraries)
    }

    @Test
    fun `the library list orders servers and libraries by name ignoring case`() = runTest {
        val catalog = LibraryCatalogImpl(
            FakeServers(listOf(config("b", "Beta"), config("a", "alpha"))),
            FakeBooks(listOf(
                LibraryInfo("a", "1", "banana"), LibraryInfo("a", "2", "Cherry"), LibraryInfo("a", "3", "apple"),
                LibraryInfo("b", "4", "zed"), LibraryInfo("b", "5", "Yak")
            ))
        )

        val list = catalog.observeServerLibraries().first()

        assertEquals(listOf("alpha", "Beta"), list.map { it.server.name })
        assertEquals(listOf("apple", "banana", "Cherry"), list[0].libraries.map { it.name })
        assertEquals(listOf("Yak", "zed"), list[1].libraries.map { it.name })
    }

    @Test
    fun `a server without library rows has an empty list`() = runTest {
        val catalog = LibraryCatalogImpl(FakeServers(listOf(config("a"))), FakeBooks(emptyList()))

        assertEquals(emptyList(), catalog.observeServerLibraries().first().single().libraries)
    }

    @Test
    fun `a server with every library off stays listed`() = runTest {
        val catalog = LibraryCatalogImpl(
            FakeServers(listOf(config("a").copy(excludedLibraryIds = setOf("l1", "l2")))),
            FakeBooks(listOf(LibraryInfo("a", "l1", "A"), LibraryInfo("a", "l2", "B")))
        )

        val list = catalog.observeServerLibraries().first()

        assertEquals(1, list.size)
        assertEquals(listOf(false, false), list.single().libraries.map { it.enabled })
    }

    @Test
    fun `the library list follows both sources during one collection`() = runTest {
        val servers = FakeServers(listOf(config("a")))
        val libs = FakeBooks(listOf(LibraryInfo("a", "l1", "A")))
        val catalog = LibraryCatalogImpl(servers, libs)
        val got = mutableListOf<List<ServerLibraries>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.observeServerLibraries().toList(got) }

        servers.state.value = listOf(config("a").copy(excludedLibraryIds = setOf("l1")))
        libs.libs.value = libs.libs.value + LibraryInfo("a", "l2", "B")

        assertTrue(got.size >= 3)
        assertEquals(listOf(LibraryToggle("l1", "A", false), LibraryToggle("l2", "B", true)), got.last().single().libraries)
    }

    @Test
    fun `turning a library off through the repository flips it in the library list`() = runTest {
        val r = rig()
        val catalog = LibraryCatalogImpl(r.repo, FakeBooks(listOf(LibraryInfo("a", "lib1", "One"), LibraryInfo("a", "lib2", "Two"))))
        val got = mutableListOf<List<ServerLibraries>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.observeServerLibraries().toList(got) }
        assertEquals(listOf(true, true), got.last().first { it.server.id == "a" }.libraries.map { it.enabled })

        r.repo.setLibraryEnabled("a", "lib1", false)

        assertEquals(listOf(false, true), got.last().first { it.server.id == "a" }.libraries.map { it.enabled })
    }

    @Test
    fun `a book without a library id survives turning a library off`() = runTest {
        // Rows cached before Room version 3 have no library; only the next sync cleans them up.
        val r = rig()
        r.bookDao.books.value = r.bookDao.books.value + book("old", "a", "")

        r.repo.setLibraryEnabled("a", "lib1", false)

        assertTrue("old" in r.bookIds())
    }
}
