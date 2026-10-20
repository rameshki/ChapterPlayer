package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loading one book's details: the source is asked for that book and only that book's cached chapters change. */
class BookRepositoryDetailsTest {

    private class FakeBookDao(initial: List<BookEntity>) : BookDao {
        val books = MutableStateFlow(initial)
        var chapterWrites = 0
        override fun observeAll(): Flow<List<BookEntity>> = books
        override fun observeBySource(source: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.source == source } }
        override fun observeByServer(serverId: String): Flow<List<BookEntity>> = books.map { it.filter { b -> b.serverId == serverId } }
        override suspend fun getById(id: String): BookEntity? = books.value.firstOrNull { it.id == id }
        override suspend fun getByServer(serverId: String): List<BookEntity> = books.value.filter { it.serverId == serverId }
        override suspend fun insertAll(books: List<BookEntity>) { val ids = books.map { it.id }.toSet(); this.books.value = this.books.value.filter { it.id !in ids } + books }
        override suspend fun insert(book: BookEntity) { books.value = books.value + book }
        override suspend fun deleteByServer(serverId: String) { books.value = books.value.filter { it.serverId != serverId } }
        override suspend fun deleteByLibrary(serverId: String, libraryId: String) {}
        override suspend fun deleteAll() { books.value = emptyList() }
        override suspend fun getLastCacheTime(serverId: String): Long? = null
        override suspend fun updateChapters(id: String, json: String) {
            chapterWrites++
            books.value = books.value.map { if (it.id == id) it.copy(chaptersJson = json) else it }
        }

        fun chaptersOf(id: String): List<Chapter> = ChapterJson.decode(books.value.first { it.id == id }.chaptersJson)
    }

    private class NoProgressDao : ProgressDao {
        override fun observe(bookId: String): Flow<ProgressEntity?> = flowOf(null)
        override suspend fun get(bookId: String): ProgressEntity? = null
        override fun observeInProgress(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override suspend fun upsert(progress: ProgressEntity) {}
        override suspend fun delete(bookId: String) {}
    }

    private class DetailsSource(
        override val type: ServerSource,
        private val answer: suspend () -> BookDetails?,
    ) : LibrarySource {
        val asked = mutableListOf<Pair<ServerConfig, String>>()
        override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = emptyList()
        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = emptyList()
        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()
        override suspend fun fetchBookDetails(server: ServerConfig, bookId: String): BookDetails? {
            asked += server to bookId
            return answer()
        }
    }

    private val oldChapters = listOf(Chapter(1, "Old one", 0, 500), Chapter(2, "Old two", 500, 1_000))
    private val newChapters = listOf(Chapter(0, "New one", 0, 700), Chapter(1, "New two", 700, 2_000))

    private fun details(chapters: List<Chapter>) = BookDetails(chapters, "About", listOf("Fantasy"), "2019", emptyList())

    private fun entity(id: String, serverId: String = "srv1", chapters: List<Chapter> = oldChapters) = BookEntity(
        id, "T$id", "A", "N", 1L, ChapterJson.encode(chapters), "u", null, "ABS", serverId, 0L
    )

    private fun server(id: String = "srv1", source: ServerSource = ServerSource.ABS) =
        ServerConfig(id, source, "S", "http://s.test", "tok-secret")

    private fun repo(dao: BookDao, source: LibrarySource, servers: List<ServerConfig> = listOf(server())) =
        BookRepositoryImpl(
            dao, NoProgressDao(), InMemoryLibraryDao(), InMemoryCollectionDao(),
            FakeServerList(servers), listOf(source)
        )

    @Test
    fun `stores the chapters for that book only`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a"), entity("abs_b")))
        val source = DetailsSource(ServerSource.ABS) { details(newChapters) }

        val result = repo(dao, source).loadDetails("abs_a")

        assertEquals(details(newChapters), result.getOrThrow())
        assertEquals(newChapters, dao.chaptersOf("abs_a"))
        assertEquals(oldChapters, dao.chaptersOf("abs_b"))
        assertEquals(1, dao.chapterWrites)
        assertEquals(listOf(server() to "abs_a"), source.asked)
    }

    @Test
    fun `a failing source leaves the cached chapters and returns the failure`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a")))
        val failure = IOException("HTTP 500")

        val result = repo(dao, DetailsSource(ServerSource.ABS) { throw failure }).loadDetails("abs_a")

        assertEquals(failure, result.exceptionOrNull())
        assertEquals(oldChapters, dao.chaptersOf("abs_a"))
        assertEquals(0, dao.chapterWrites)
    }

    @Test
    fun `an unknown book is a failure and asks nobody`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a")))
        val source = DetailsSource(ServerSource.ABS) { details(newChapters) }

        val result = repo(dao, source).loadDetails("abs_missing")

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue(source.asked.isEmpty())
        assertEquals(0, dao.chapterWrites)
    }

    @Test
    fun `a book whose server is gone is a failure without secrets`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a", serverId = "gone")))
        val source = DetailsSource(ServerSource.ABS) { details(newChapters) }

        val result = repo(dao, source).loadDetails("abs_a")

        val error = assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue("tok-secret" !in error.message.orEmpty())
        assertTrue(source.asked.isEmpty())
        assertEquals(oldChapters, dao.chaptersOf("abs_a"))
    }

    @Test
    fun `no source for the server type is a failure`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a")))

        val result = repo(dao, DetailsSource(ServerSource.PLEX) { details(newChapters) }).loadDetails("abs_a")

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals(0, dao.chapterWrites)
    }

    @Test
    fun `a source with no details gives success with null and writes nothing`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a")))

        val result = repo(dao, DetailsSource(ServerSource.ABS) { null }).loadDetails("abs_a")

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(0, dao.chapterWrites)
        assertEquals(oldChapters, dao.chaptersOf("abs_a"))
    }

    @Test
    fun `an empty chapter list from the server replaces the cached chapters`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a")))

        val result = repo(dao, DetailsSource(ServerSource.ABS) { details(emptyList()) }).loadDetails("abs_a")

        assertTrue(result.isSuccess)
        assertEquals(emptyList(), dao.chaptersOf("abs_a"))
        assertEquals(1, dao.chapterWrites)
    }

    @Test
    fun `cancellation propagates and is not a failure result`() = runTest {
        val dao = FakeBookDao(listOf(entity("abs_a")))

        assertFailsWith<CancellationException> {
            repo(dao, DetailsSource(ServerSource.ABS) { throw CancellationException("stop") }).loadDetails("abs_a")
        }
        assertEquals(0, dao.chapterWrites)
    }
}
