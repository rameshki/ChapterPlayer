package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.source.LibrarySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the repository syncs a server: fetch through the right source, then replace that server's cache. */
class BookRepositorySyncTest {

    private class FakeBookDao(initial: List<BookEntity> = emptyList()) : BookDao {
        val stored = initial.associateBy { it.id }.toMutableMap()
        val calls = mutableListOf<String>()

        override fun observeAll(): Flow<List<BookEntity>> = flowOf(stored.values.toList())
        override fun observeBySource(source: String): Flow<List<BookEntity>> = flowOf(stored.values.filter { it.source == source })
        override fun observeByServer(serverId: String): Flow<List<BookEntity>> = flowOf(stored.values.filter { it.serverId == serverId })
        override suspend fun getById(id: String): BookEntity? = stored[id]
        override suspend fun insertAll(books: List<BookEntity>) { calls += "insertAll:${books.size}"; books.forEach { stored[it.id] = it } }
        override suspend fun insert(book: BookEntity) { stored[book.id] = book }
        override suspend fun deleteByServer(serverId: String) { calls += "deleteByServer:$serverId"; stored.values.removeAll { it.serverId == serverId } }
        override suspend fun deleteAll() { stored.clear() }
        override suspend fun getLastCacheTime(serverId: String): Long? = stored.values.filter { it.serverId == serverId }.maxOfOrNull { it.cachedAt }
    }

    private class FakeProgressDao : ProgressDao {
        override fun observe(bookId: String): Flow<ProgressEntity?> = flowOf(null)
        override suspend fun get(bookId: String): ProgressEntity? = null
        override fun observeInProgress(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override suspend fun upsert(progress: ProgressEntity) {}
        override suspend fun delete(bookId: String) {}
    }

    private class FakeSource(
        override val type: ServerSource,
        private val books: List<AudioBook> = emptyList(),
        private val failure: Throwable? = null,
    ) : LibrarySource {
        val asked = mutableListOf<ServerConfig>()

        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> {
            asked += server
            failure?.let { throw it }
            return books
        }
    }

    private fun server(id: String = "srv1", source: ServerSource = ServerSource.ABS) =
        ServerConfig(id = id, source = source, name = "S", baseUrl = "http://s.test", token = "tok")

    private fun book(id: String, serverId: String = "srv1", source: ServerSource = ServerSource.ABS) = AudioBook(
        id = id, title = "Title $id", author = "Author", narrator = "Narrator", durationMs = 3_600_000,
        chapters = listOf(Chapter(id = 1, title = "One", startMs = 0, endMs = 1_000)),
        streamUrl = "http://s.test/play/$id", coverUrl = "http://s.test/cover/$id", source = source, serverId = serverId,
    )

    private fun entity(id: String, serverId: String) = BookEntity(
        id = id, title = "Old", author = "Old", narrator = "", durationMs = 0, chaptersJson = "[]",
        streamUrl = "", coverUrl = null, source = "ABS", serverId = serverId, cachedAt = 1,
    )

    private fun repository(dao: BookDao, vararg sources: LibrarySource) =
        BookRepositoryImpl(dao, FakeProgressDao(), sources.toList(), clock = { 1_234L })

    @Test
    fun `stores the books the source returns, stamped with the sync time`() = runTest {
        val dao = FakeBookDao()
        val result = repository(dao, FakeSource(ServerSource.ABS, listOf(book("a"), book("b")))).syncBooksFromServer(server())

        assertTrue(result.isSuccess)
        val stored = dao.stored.getValue("a")
        assertEquals("Title a", stored.title)
        assertEquals("Author", stored.author)
        assertEquals("Narrator", stored.narrator)
        assertEquals(3_600_000L, stored.durationMs)
        assertEquals("http://s.test/play/a", stored.streamUrl)
        assertEquals("http://s.test/cover/a", stored.coverUrl)
        assertEquals("ABS", stored.source)
        assertEquals("srv1", stored.serverId)
        assertEquals(1_234L, stored.cachedAt)
        assertEquals(listOf(Chapter(1, "One", 0, 1_000)), ChapterJson.decode(stored.chaptersJson))
        assertEquals(setOf("a", "b"), dao.stored.keys)
    }

    @Test
    fun `replaces only that server's books`() = runTest {
        val dao = FakeBookDao(listOf(entity("old", "srv1"), entity("other", "srv2")))

        repository(dao, FakeSource(ServerSource.ABS, listOf(book("new")))).syncBooksFromServer(server())

        assertEquals(setOf("new", "other"), dao.stored.keys)
        assertEquals(listOf("deleteByServer:srv1", "insertAll:1"), dao.calls)
    }

    @Test
    fun `asks only the source for the server's type`() = runTest {
        val abs = FakeSource(ServerSource.ABS, listOf(book("a")))
        val plex = FakeSource(ServerSource.PLEX, listOf(book("p", source = ServerSource.PLEX)))

        repository(FakeBookDao(), abs, plex).syncBooksFromServer(server(source = ServerSource.PLEX))

        assertTrue(abs.asked.isEmpty())
        assertEquals(1, plex.asked.size)
    }

    @Test
    fun `keeps the cached books when fetching fails`() = runTest {
        val dao = FakeBookDao(listOf(entity("old", "srv1")))
        val failure = IOException("HTTP 401 Unauthorized")

        val result = repository(dao, FakeSource(ServerSource.ABS, failure = failure)).syncBooksFromServer(server())

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(setOf("old"), dao.stored.keys)
        assertFalse(dao.calls.any { it.startsWith("deleteByServer") })
    }

    @Test
    fun `fails when no source handles the server type`() = runTest {
        val dao = FakeBookDao(listOf(entity("old", "srv1")))

        val result = repository(dao, FakeSource(ServerSource.ABS)).syncBooksFromServer(server(source = ServerSource.PLEX))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("PLEX"))
        assertEquals(setOf("old"), dao.stored.keys)
    }

    @Test
    fun `a server with no books empties its cache`() = runTest {
        val dao = FakeBookDao(listOf(entity("old", "srv1")))

        repository(dao, FakeSource(ServerSource.ABS, emptyList())).syncBooksFromServer(server())

        assertTrue(dao.stored.isEmpty())
    }
}
