package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.CollectionDao
import com.chapterplayer.data.local.dao.LibraryDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.LibraryEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.model.ChapterJson
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.source.LibrarySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        override suspend fun deleteByLibrary(serverId: String, libraryId: String) { stored.values.removeAll { it.serverId == serverId && it.libraryId == libraryId } }
        override suspend fun deleteAll() { stored.clear() }
        override suspend fun getLastCacheTime(serverId: String): Long? = stored.values.filter { it.serverId == serverId }.maxOfOrNull { it.cachedAt }
    }

    private class FakeProgressDao : ProgressDao {
        override fun observe(bookId: String): Flow<ProgressEntity?> = flowOf(null)
        override suspend fun get(bookId: String): ProgressEntity? = null
        override fun observeInProgress(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<ProgressEntity>> = flowOf(emptyList())
        override suspend fun upsert(progress: ProgressEntity) {}
        override suspend fun delete(bookId: String) {}
    }

    private class FakeLibraryDao(initial: List<LibraryEntity> = emptyList()) : LibraryDao {
        val stored = initial.toMutableList()
        override fun observeAll(): Flow<List<LibraryEntity>> = flowOf(stored.toList())
        override suspend fun deleteForServer(serverId: String) { stored.removeAll { it.serverId == serverId } }
        override suspend fun insertAll(rows: List<LibraryEntity>) {
            rows.forEach { row -> stored.removeAll { it.serverId == row.serverId && it.id == row.id }; stored += row }
        }
    }

    private class FakeCollectionDao(
        initial: List<CollectionEntity> = emptyList(),
        initialBooks: List<CollectionBookEntity> = emptyList(),
    ) : CollectionDao {
        val collections = initial.toMutableList()
        val books = initialBooks.toMutableList()
        override fun observeCollections(): Flow<List<CollectionEntity>> = flowOf(collections.sortedBy { it.name.lowercase() })
        override fun observeCollectionBooks(): Flow<List<CollectionBookEntity>> = flowOf(books.toList())
        override suspend fun deleteCollectionsForServer(serverId: String) { collections.removeAll { it.serverId == serverId } }
        override suspend fun deleteCollectionBooksForServer(serverId: String) { books.removeAll { it.serverId == serverId } }
        override suspend fun insertCollections(rows: List<CollectionEntity>) {
            rows.forEach { row -> collections.removeAll { it.serverId == row.serverId && it.id == row.id }; collections += row }
        }
        override suspend fun insertCollectionBooks(rows: List<CollectionBookEntity>) {
            rows.forEach { row ->
                books.removeAll { it.serverId == row.serverId && it.collectionId == row.collectionId && it.bookId == row.bookId }
                books += row
            }
        }
    }

    private class FakeSource(
        override val type: ServerSource,
        private val books: List<AudioBook> = emptyList(),
        private val failure: Throwable? = null,
        private val libraries: List<LibraryInfo> = emptyList(),
        private val librariesFailure: Throwable? = null,
        private val collections: List<BookCollection> = emptyList(),
        private val collectionsFailure: Throwable? = null,
    ) : LibrarySource {
        val asked = mutableListOf<ServerConfig>()

        override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> {
            librariesFailure?.let { throw it }
            return libraries
        }

        override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> {
            collectionsFailure?.let { throw it }
            return collections
        }

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

    private fun repository(
        dao: BookDao,
        vararg sources: LibrarySource,
        libraryDao: LibraryDao = FakeLibraryDao(),
        collectionDao: CollectionDao = FakeCollectionDao(),
        inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    ) = BookRepositoryImpl(
        dao, FakeProgressDao(), libraryDao, collectionDao, sources.toList(), clock = { 1_234L }, inTransaction = inTransaction
    )

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
    fun `a sync writes the books, libraries and collections together`() = runTest {
        val dao = FakeBookDao()
        val libraryDao = FakeLibraryDao()
        val collectionDao = FakeCollectionDao()
        val source = FakeSource(
            ServerSource.ABS,
            books = listOf(book("a")),
            libraries = listOf(LibraryInfo("srv1", "lib1", "Books"), LibraryInfo("srv1", "lib2", "More")),
            collections = listOf(BookCollection("srv1", "c1", "Favourites", listOf("abs_b", "abs_a"))),
        )

        val result = repository(dao, source, libraryDao = libraryDao, collectionDao = collectionDao).syncBooksFromServer(server())

        assertTrue(result.isSuccess)
        assertEquals(setOf("a"), dao.stored.keys)
        assertEquals(listOf(LibraryEntity("srv1", "lib1", "Books"), LibraryEntity("srv1", "lib2", "More")), libraryDao.stored)
        assertEquals(listOf(CollectionEntity("srv1", "c1", "Favourites")), collectionDao.collections)
        assertEquals(
            listOf(CollectionBookEntity("srv1", "c1", "abs_b", 0), CollectionBookEntity("srv1", "c1", "abs_a", 1)),
            collectionDao.books
        )
    }

    @Test
    fun `collections are listed by name ignoring case, so a lower-case name sits with its neighbours`() = runTest {
        val collectionDao = FakeCollectionDao(
            listOf(
                CollectionEntity("srv1", "c1", "Zeta"),
                CollectionEntity("srv1", "c2", "alpha"),
                CollectionEntity("srv1", "c3", "Beta"),
            )
        )

        val names = repository(FakeBookDao(), collectionDao = collectionDao).observeCollections().first().map { it.name }

        assertEquals(listOf("alpha", "Beta", "Zeta"), names)
    }

    @Test
    fun `a sync replaces only that server's libraries and collections`() = runTest {
        val libraryDao = FakeLibraryDao(listOf(LibraryEntity("srv1", "old", "Old"), LibraryEntity("srv2", "keep", "Keep")))
        val collectionDao = FakeCollectionDao(
            listOf(CollectionEntity("srv1", "oldc", "Old"), CollectionEntity("srv2", "keepc", "Keep")),
            listOf(CollectionBookEntity("srv1", "oldc", "abs_x", 0), CollectionBookEntity("srv2", "keepc", "abs_y", 0)),
        )
        val source = FakeSource(ServerSource.ABS, libraries = listOf(LibraryInfo("srv1", "new", "New")))

        repository(FakeBookDao(), source, libraryDao = libraryDao, collectionDao = collectionDao).syncBooksFromServer(server())

        assertEquals(listOf(LibraryEntity("srv2", "keep", "Keep"), LibraryEntity("srv1", "new", "New")), libraryDao.stored)
        assertEquals(listOf(CollectionEntity("srv2", "keepc", "Keep")), collectionDao.collections)
        assertEquals(listOf(CollectionBookEntity("srv2", "keepc", "abs_y", 0)), collectionDao.books)
    }

    @Test
    fun `a failing library call keeps the old libraries and still syncs the books`() = runTest {
        val dao = FakeBookDao()
        val libraryDao = FakeLibraryDao(listOf(LibraryEntity("srv1", "old", "Old")))
        val source = FakeSource(ServerSource.ABS, books = listOf(book("a")), librariesFailure = IOException("down"))

        val result = repository(dao, source, libraryDao = libraryDao).syncBooksFromServer(server())

        assertTrue(result.isSuccess)
        assertEquals(setOf("a"), dao.stored.keys)
        assertEquals(listOf(LibraryEntity("srv1", "old", "Old")), libraryDao.stored)
    }

    @Test
    fun `a failing collections call keeps the old collections and still syncs the books and libraries`() = runTest {
        val dao = FakeBookDao()
        val libraryDao = FakeLibraryDao()
        val collectionDao = FakeCollectionDao(
            listOf(CollectionEntity("srv1", "oldc", "Old")),
            listOf(CollectionBookEntity("srv1", "oldc", "abs_x", 0)),
        )
        val source = FakeSource(
            ServerSource.ABS, books = listOf(book("a")), libraries = listOf(LibraryInfo("srv1", "lib1", "Books")),
            collectionsFailure = IOException("down"),
        )

        val result = repository(dao, source, libraryDao = libraryDao, collectionDao = collectionDao).syncBooksFromServer(server())

        assertTrue(result.isSuccess)
        assertEquals(setOf("a"), dao.stored.keys)
        assertEquals(1, libraryDao.stored.size)
        assertEquals(listOf(CollectionEntity("srv1", "oldc", "Old")), collectionDao.collections)
        assertEquals(listOf(CollectionBookEntity("srv1", "oldc", "abs_x", 0)), collectionDao.books)
    }

    @Test
    fun `a cancelled library call cancels the sync instead of being swallowed`() = runTest {
        val source = FakeSource(ServerSource.ABS, books = listOf(book("a")), librariesFailure = CancellationException("stop"))

        val dao = FakeBookDao()

        val result = repository(dao, source).syncBooksFromServer(server())

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `a failing book fetch leaves libraries and collections alone too`() = runTest {
        val libraryDao = FakeLibraryDao(listOf(LibraryEntity("srv1", "old", "Old")))
        val collectionDao = FakeCollectionDao(listOf(CollectionEntity("srv1", "oldc", "Old")))
        val source = FakeSource(
            ServerSource.ABS, failure = IOException("401"), libraries = listOf(LibraryInfo("srv1", "new", "New")),
            collections = listOf(BookCollection("srv1", "newc", "New", emptyList())),
        )

        val result = repository(FakeBookDao(), source, libraryDao = libraryDao, collectionDao = collectionDao).syncBooksFromServer(server())

        assertTrue(result.isFailure)
        assertEquals(listOf(LibraryEntity("srv1", "old", "Old")), libraryDao.stored)
        assertEquals(listOf(CollectionEntity("srv1", "oldc", "Old")), collectionDao.collections)
    }

    @Test
    fun `observed collections list their books in position order`() = runTest {
        val collectionDao = FakeCollectionDao(
            listOf(CollectionEntity("srv1", "c1", "First"), CollectionEntity("srv1", "c2", "Empty")),
            listOf(
                CollectionBookEntity("srv1", "c1", "abs_c", 2),
                CollectionBookEntity("srv1", "c1", "abs_a", 0),
                CollectionBookEntity("srv1", "c1", "abs_b", 1),
            ),
        )

        val collections = repository(FakeBookDao(), collectionDao = collectionDao).observeCollections().first()

        assertEquals(
            listOf(
                BookCollection("srv1", "c2", "Empty", emptyList()),
                BookCollection("srv1", "c1", "First", listOf("abs_a", "abs_b", "abs_c")),
            ),
            collections
        )
    }

    @Test
    fun `a collection listing the same book twice ends with one row`() = runTest {
        val collectionDao = FakeCollectionDao()
        val source = FakeSource(
            ServerSource.ABS, collections = listOf(BookCollection("srv1", "c1", "Dup", listOf("abs_a", "abs_a", "abs_b")))
        )

        repository(FakeBookDao(), source, collectionDao = collectionDao).syncBooksFromServer(server())

        assertEquals(listOf("abs_a", "abs_b"), collectionDao.books.map { it.bookId }.sorted())
    }

    @Test
    fun `every write of a sync happens inside one transaction and no fetch does`() = runTest {
        val events = mutableListOf<String>()
        val dao = object : BookDao by FakeBookDao() {
            override suspend fun deleteByServer(serverId: String) { events += "write:books" }
            override suspend fun insertAll(books: List<BookEntity>) { events += "write:books" }
        }
        val libraryDao = object : LibraryDao by FakeLibraryDao() {
            override suspend fun deleteForServer(serverId: String) { events += "write:libraries" }
            override suspend fun insertAll(rows: List<LibraryEntity>) { events += "write:libraries" }
        }
        val collectionDao = object : CollectionDao by FakeCollectionDao() {
            override suspend fun deleteCollectionsForServer(serverId: String) { events += "write:collections" }
            override suspend fun insertCollections(rows: List<CollectionEntity>) { events += "write:collections" }
        }
        val source = object : LibrarySource {
            override val type = ServerSource.ABS
            override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> { events += "fetch:books"; return listOf(book("a")) }
            override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> { events += "fetch:libraries"; return listOf(LibraryInfo("srv1", "l", "L")) }
            override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> { events += "fetch:collections"; return listOf(BookCollection("srv1", "c", "C", emptyList())) }
        }

        repository(dao, source, libraryDao = libraryDao, collectionDao = collectionDao, inTransaction = { block ->
            events += "begin"
            block()
            events += "end"
        }).syncBooksFromServer(server())

        val begin = events.indexOf("begin")
        val end = events.indexOf("end")
        assertTrue(events.indexOf("fetch:collections") < begin)
        assertTrue(events.filter { it.startsWith("fetch") }.all { events.indexOf(it) < begin })
        val writes = events.withIndex().filter { it.value.startsWith("write") }
        assertEquals(setOf("write:books", "write:libraries", "write:collections"), writes.map { it.value }.toSet())
        assertTrue(writes.all { it.index in (begin + 1) until end })
    }

    @Test
    fun `a write that fails inside the transaction reaches the wrapper, which can roll back`() = runTest {
        val dao = FakeBookDao(listOf(entity("old", "srv1")))
        val libraryDao = object : LibraryDao by FakeLibraryDao() {
            override suspend fun insertAll(rows: List<LibraryEntity>) { throw IOException("disk full") }
        }
        val source = FakeSource(ServerSource.ABS, books = listOf(book("new")), libraries = listOf(LibraryInfo("srv1", "l", "L")))
        var seen: Throwable? = null

        val result = repository(dao, source, libraryDao = libraryDao, inTransaction = { block ->
            val snapshot = dao.stored.toMap()
            try {
                block()
            } catch (e: Throwable) {
                seen = e
                dao.stored.clear()
                dao.stored.putAll(snapshot) // what a rolled-back transaction does
                throw e
            }
        }).syncBooksFromServer(server())

        assertTrue(seen is IOException)
        assertTrue(result.isFailure)
        assertEquals(setOf("old"), dao.stored.keys)
    }

    @Test
    fun `observed libraries are the stored ones`() = runTest {
        val libraryDao = FakeLibraryDao(listOf(LibraryEntity("srv1", "lib1", "Books")))

        assertEquals(
            listOf(LibraryInfo("srv1", "lib1", "Books")),
            repository(FakeBookDao(), libraryDao = libraryDao).observeLibraries().first()
        )
    }

    @Test
    fun `a server with no books empties its cache`() = runTest {
        val dao = FakeBookDao(listOf(entity("old", "srv1")))

        repository(dao, FakeSource(ServerSource.ABS, emptyList())).syncBooksFromServer(server())

        assertTrue(dao.stored.isEmpty())
    }
}
