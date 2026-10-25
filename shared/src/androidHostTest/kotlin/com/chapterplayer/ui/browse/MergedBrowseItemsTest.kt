package com.chapterplayer.ui.browse

import com.chapterplayer.data.merge.AppSourceMerging
import com.chapterplayer.data.merge.BookMergeRepository
import com.chapterplayer.data.merge.FixedSourceMerging
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.sync.NoServerStatusStore
import com.chapterplayer.data.sync.ServerStatusRecord
import com.chapterplayer.domain.browse.MergeContext
import com.chapterplayer.domain.browse.MergeOverrides
import com.chapterplayer.domain.browse.SourcePreference
import com.chapterplayer.domain.browse.reachableServerIds
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MergedBrowseItemsTest {

    private fun abs(id: String) = testBook(id, title = "Same Book", author = "Same Author").copy(serverId = "abs1", durationMs = 3_600_000L)
    private fun plex(id: String) = abs(id).copy(serverId = "plex1", source = ServerSource.PLEX)

    @Test
    fun `without merging every book is its own entry`() = runTest {
        val books = FakeBookRepository(listOf(abs("a"), plex("p")))
        assertEquals(2, observeBrowseItems(books, FakeDownloadStates()).first().size)
    }

    @Test
    fun `with merging the same book is one entry`() = runTest {
        val books = FakeBookRepository(listOf(abs("a"), plex("p")))
        val items = observeBrowseItems(books, FakeDownloadStates(), FixedSourceMerging(MergeContext(onlineServerIds = setOf("abs1", "plex1")))).first()
        assertEquals(listOf("a"), items.map { it.book.id })
        assertEquals(listOf("a", "p"), items.single().sources.map { it.book.id })
    }

    @Test
    fun `the app merging follows the corrections, the preference and the network`() = runTest {
        val books = FakeBookRepository(listOf(abs("a"), plex("p")))
        val overrides = MutableStateFlow(MergeOverrides())
        val merges = object : BookMergeRepository {
            override val overrides: Flow<MergeOverrides> = overrides
            override suspend fun split(bookId: String, otherIds: Collection<String>) {}
            override suspend fun merge(bookId: String, otherId: String) {}
        }
        val online = MutableStateFlow(true)
        val monitor = object : ConnectivityMonitor { override val isOnline: Flow<Boolean> = online }
        val settings = FakeAppSettings()
        val servers = FakeServerList(listOf(
            ServerConfig("abs1", ServerSource.ABS, "A", "http://a", "t"),
            ServerConfig("plex1", ServerSource.PLEX, "P", "http://p", "t"),
        ))
        val merging = AppSourceMerging(merges, settings, monitor, servers, NoServerStatusStore)
        val flow = observeBrowseItems(books, FakeDownloadStates(), merging)

        assertEquals("a", flow.first().single().book.id)
        settings.sourcePreferenceFlow.value = SourcePreference.PREFER_PLEX
        assertEquals("p", flow.first().single().book.id)
        assertEquals(setOf("abs1", "plex1"), merging.context.first()?.onlineServerIds)
        online.value = false
        assertEquals(emptySet(), merging.context.first()?.onlineServerIds)
        overrides.value = MergeOverrides(splits = setOf("a" to "p"))
        assertEquals(2, flow.first().size)
    }

    @Test
    fun `a downloaded copy shows as the entry`() = runTest {
        val books = FakeBookRepository(listOf(abs("a"), plex("p")))
        val downloads = FakeDownloadStates()
        downloads.states.value = mapOf("p" to DownloadState.DOWNLOADED)
        val items = observeBrowseItems(books, downloads, FixedSourceMerging()).first()
        assertEquals("p", items.single().book.id)
    }

    @Test
    fun `a server is reachable unless offline, downloaded only or its last sync failed`() {
        val records = mapOf(
            "ok" to ServerStatusRecord(lastSuccessAtMs = 20, lastErrorAtMs = 10),
            "bad" to ServerStatusRecord(lastSuccessAtMs = 10, lastErrorAtMs = 20),
            "never" to ServerStatusRecord(lastErrorAtMs = 5),
        )
        val ids = listOf("ok", "bad", "never", "new")
        assertEquals(setOf("ok", "new"), reachableServerIds(ids, records, online = true, downloadedOnly = false))
        assertEquals(emptySet(), reachableServerIds(ids, records, online = false, downloadedOnly = false))
        assertEquals(emptySet(), reachableServerIds(ids, records, online = true, downloadedOnly = true))
    }
}
