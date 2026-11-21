package com.chapterplayer.data.backup

import com.chapterplayer.data.downloads.FakeBookDao
import com.chapterplayer.data.local.dao.BookMergeDao
import com.chapterplayer.data.local.dao.FavouriteDao
import com.chapterplayer.data.local.dao.UpNextDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.BookMergeEntity
import com.chapterplayer.data.local.entity.FavouriteEntity
import com.chapterplayer.data.local.entity.UpNextEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.settings.AppFont
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupServiceTest {
    private class Favs : FavouriteDao {
        val rows = MutableStateFlow<List<FavouriteEntity>>(emptyList())
        override fun observeIds(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun insertAll(rows: List<FavouriteEntity>) {
            val known = this.rows.value.map { it.bookId }.toSet()
            this.rows.value = this.rows.value + rows.filter { it.bookId !in known }
        }
        override suspend fun getAll(): List<FavouriteEntity> = rows.value
        override suspend fun existing(bookIds: List<String>): List<String> = emptyList()
        override suspend fun deleteAll(bookIds: List<String>) {}
    }

    private class Queue : UpNextDao {
        val rows = MutableStateFlow<List<UpNextEntity>>(emptyList())
        override fun observeAll(): Flow<List<UpNextEntity>> = rows
        override suspend fun getAll(): List<UpNextEntity> = rows.value.sortedBy { it.position }
        override suspend fun insertAll(rows: List<UpNextEntity>) {
            val ids = rows.map { it.bookId }.toSet()
            this.rows.value = this.rows.value.filterNot { it.bookId in ids } + rows
        }
        override suspend fun deleteAll() { rows.value = emptyList() }
        override suspend fun deleteFor(bookIds: List<String>) {}
        override suspend fun replaceAll(rows: List<UpNextEntity>) { this.rows.value = rows }
    }

    private class Merges : BookMergeDao {
        val rows = MutableStateFlow<List<BookMergeEntity>>(emptyList())
        override fun observeAll(): Flow<List<BookMergeEntity>> = rows
        override suspend fun getAll(): List<BookMergeEntity> = rows.value
        override suspend fun upsertAll(rows: List<BookMergeEntity>) {
            val byKey = LinkedHashMap<Pair<String, String>, BookMergeEntity>()
            for (r in this.rows.value + rows) byKey[r.bookIdA to r.bookIdB] = r
            this.rows.value = byKey.values.toList()
        }
        override suspend fun deleteKind(a: String, b: String, kind: String) {}
        override suspend fun deleteForBooks(bookIds: List<String>) {}
    }

    private fun server(id: String, url: String) =
        ServerConfig(id = id, source = ServerSource.ABS, name = "n", baseUrl = url, token = "SECRET-TOKEN")

    private fun entity(id: String, serverId: String, title: String = "Title $id") =
        BookEntity.fromDomain(testBook(id, title = title).copy(serverId = serverId), cachedAt = 0L)

    /** One install: its servers and books, and its stores. */
    private class Install(
        val servers: FakeServerList,
        val books: FakeBookDao,
        val favs: Favs = Favs(),
        val queue: Queue = Queue(),
        val merges: Merges = Merges(),
        val settings: FakeAppSettings = FakeAppSettings(),
    ) {
        val service = BackupService(books, servers, favs, queue, merges, settings, now = { 500L })
    }

    @Test
    fun `an export from one install restores favourites queue corrections and settings on another`() = runTest {
        val first = Install(
            FakeServerList(listOf(server("local-1", "https://Abs.Example:13378/"))),
            FakeBookDao(entity("b1", "local-1"), entity("b2", "local-1")),
        )
        first.favs.insertAll(listOf(FavouriteEntity("b1", 42L)))
        first.queue.insertAll(listOf(UpNextEntity("b2", 0, 1L), UpNextEntity("b1", 1, 1L)))
        first.merges.upsertAll(listOf(BookMergeEntity("b1", "b2", "SPLIT")))
        first.settings.setThemeMode(ThemeMode.LIGHT)
        first.settings.setAppFont(AppFont.entries.last())
        first.settings.setSkipBackSeconds(60)
        first.settings.setPlaybackSpeed(1.75f)
        first.settings.setResumeOnCarConnect(false)
        first.settings.setDownloadWifiOnly(false)
        val text = first.service.export()

        // Another install: its own server id for the same address, the same books.
        val second = Install(
            FakeServerList(listOf(server("local-77", "http://abs.example:13378"))),
            FakeBookDao(entity("b1", "local-77"), entity("b2", "local-77")),
        )
        val done = assertIs<RestoreResult.Done>(second.service.restore(text))

        assertEquals(listOf(FavouriteEntity("b1", 42L)), second.favs.rows.value)
        assertEquals(listOf("b2", "b1"), second.queue.getAll().map { it.bookId })
        assertEquals(listOf(BookMergeEntity("b1", "b2", "SPLIT")), second.merges.rows.value)
        assertEquals(ThemeMode.LIGHT, second.settings.theme.value)
        assertEquals(60, second.settings.skipBack.value)
        assertEquals(1.75f, second.settings.speed.value)
        // The settings that belong to the phone stay as they were.
        assertTrue(second.settings.carResume.value)
        assertTrue(second.settings.wifiOnly.value)
        assertTrue(done.summary.startsWith("Restored 1 favourite, 2 in Up next, 1 correction, 8 settings"), done.summary)
    }

    @Test
    fun `the export holds no token and no server id of this phone`() = runTest {
        val install = Install(FakeServerList(listOf(server("local-1", "https://abs.example"))), FakeBookDao(entity("b1", "local-1")))
        install.favs.insertAll(listOf(FavouriteEntity("b1", 1L)))
        val text = install.service.export()
        assertFalse("SECRET-TOKEN" in text)
        assertFalse("local-1" in text)
        assertTrue("abs.example" in text)
    }

    @Test
    fun `entries whose book is not here are skipped and said so`() = runTest {
        val first = Install(FakeServerList(listOf(server("s", "https://abs.example"))), FakeBookDao(entity("b1", "s"), entity("b2", "s")))
        first.favs.insertAll(listOf(FavouriteEntity("b1", 1L), FavouriteEntity("b2", 2L)))
        val text = first.service.export()

        val second = Install(FakeServerList(listOf(server("s", "https://abs.example"))), FakeBookDao(entity("b1", "s")))
        val done = assertIs<RestoreResult.Done>(second.service.restore(text))

        assertEquals(listOf("b1"), second.favs.rows.value.map { it.bookId })
        assertTrue("1 skipped" in done.summary, done.summary)
    }

    @Test
    fun `restoring twice adds nothing the second time`() = runTest {
        val install = Install(FakeServerList(listOf(server("s", "https://abs.example"))), FakeBookDao(entity("b1", "s")))
        install.favs.insertAll(listOf(FavouriteEntity("b1", 1L)))
        install.queue.insertAll(listOf(UpNextEntity("b1", 0, 1L)))
        val text = install.service.export()
        install.service.restore(text)
        install.service.restore(text)
        assertEquals(1, install.favs.rows.value.size)
        assertEquals(1, install.queue.rows.value.size)
    }

    @Test
    fun `text that is not a backup or comes from a newer app changes nothing`() = runTest {
        val install = Install(FakeServerList(), FakeBookDao())
        assertIs<RestoreResult.Refused>(install.service.restore("hello"))
        val tooNew = install.service.restore("{\"format\":\"chapterplayer-backup\",\"version\":99}")
        assertTrue(assertIs<RestoreResult.Refused>(tooNew).reason.contains("newer"))
        assertEquals(ThemeMode.SYSTEM, install.settings.theme.value)
    }
}
