package com.chapterplayer.domain.browse

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFilterTest {

    private fun item(id: String, serverId: String) = BrowseItem(
        AudioBook(
            id = id, title = "Title $id", author = "Author", narrator = "Narrator", durationMs = 1_000L,
            chapters = emptyList(), streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = serverId,
        ),
        null,
        DownloadState.NOT_DOWNLOADED
    )

    private fun server(id: String, name: String = "Server $id", source: ServerSource = ServerSource.ABS) =
        ServerConfig(id, source, name, "http://$id.test", "t")

    private val items = listOf(item("a", "s1"), item("b", "s2"), item("c", "s3"), item("d", "s1"))

    private fun ids(list: List<BrowseItem>) = list.map { it.book.id }.toSet()

    // ---- the filter rule ----

    @Test
    fun `an empty server set keeps every book`() {
        assertEquals(setOf("a", "b", "c", "d"), ids(applyQuery(items, BrowseQuery())))
    }

    @Test
    fun `one server keeps only its books`() {
        assertEquals(setOf("a", "d"), ids(applyQuery(items, BrowseQuery(serverIds = setOf("s1")))))
    }

    @Test
    fun `several servers keep the books of each`() {
        assertEquals(setOf("a", "b", "d"), ids(applyQuery(items, BrowseQuery(serverIds = setOf("s1", "s2")))))
    }

    @Test
    fun `the server filter combines with the other filters`() {
        val downloaded = items.map { if (it.book.id == "a") it.copy(downloadState = DownloadState.DOWNLOADED) else it }
        val query = BrowseQuery(download = DownloadFilter.DOWNLOADED, serverIds = setOf("s1", "s2"))
        assertEquals(setOf("a"), ids(applyQuery(downloaded, query)))
    }

    @Test
    fun `a server filter counts as filtering and is not a chip of its own`() {
        val query = BrowseQuery(serverIds = setOf("s1"))
        assertTrue(isFiltering(query))
        assertEquals(FilterChip.ALL, chipFor(query))
        // The quick chips keep the server choice.
        assertEquals(setOf("s1"), queryFor(FilterChip.FINISHED, query).serverIds)
    }

    // ---- stale ids ----

    @Test
    fun `ids of servers that no longer exist drop out of the filter`() {
        val query = BrowseQuery(serverIds = setOf("s1", "gone"))
        assertEquals(setOf("s1"), pruneServerFilter(query, setOf("s1", "s2")).serverIds)
    }

    @Test
    fun `a filter made only of stale ids becomes all servers`() {
        val pruned = pruneServerFilter(BrowseQuery(serverIds = setOf("gone")), setOf("s1", "s2"))
        assertEquals(emptySet(), pruned.serverIds)
        assertEquals(setOf("a", "b", "c", "d"), ids(applyQuery(items, pruned)))
    }

    @Test
    fun `pruning keeps the other parts of the query`() {
        val query = BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.DOWNLOADED, BrowseSort.TITLE, setOf("gone"))
        assertEquals(BrowseQuery(ProgressFilter.FINISHED, DownloadFilter.DOWNLOADED, BrowseSort.TITLE), pruneServerFilter(query, setOf("s1")))
    }

    // ---- toggling ----

    @Test
    fun `unticking a server while all are shown keeps the others`() {
        assertEquals(setOf("s2", "s3"), toggleServer(emptySet(), "s1", listOf("s1", "s2", "s3")))
    }

    @Test
    fun `ticking the last unticked server goes back to all servers`() {
        assertEquals(emptySet(), toggleServer(setOf("s1", "s2"), "s3", listOf("s1", "s2", "s3")))
    }

    @Test
    fun `the last ticked server cannot be unticked`() {
        assertEquals(setOf("s2"), toggleServer(setOf("s2"), "s2", listOf("s1", "s2", "s3")))
    }

    @Test
    fun `toggling ignores stale ids in the current set`() {
        assertEquals(setOf("s1", "s2"), toggleServer(setOf("s1", "gone"), "s2", listOf("s1", "s2", "s3")))
    }

    // ---- sheet and chip ----

    @Test
    fun `the sheet offers no server section with fewer than two servers`() {
        assertEquals(emptyList(), serverFilterOptions(listOf(server("s1")), items, emptySet()))
        assertEquals(emptyList(), serverFilterOptions(emptyList(), items, emptySet()))
    }

    @Test
    fun `each server option has its name - type - unfiltered book count and tick`() {
        val servers = listOf(server("s1", "Home"), server("s2", "Office", ServerSource.PLEX), server("s3", "Empty"))
        val options = serverFilterOptions(servers, items.filter { it.book.serverId != "s3" }, setOf("s2"))

        assertEquals(
            listOf(
                ServerFilterOption("s1", "Home", "ABS", 2, selected = false),
                ServerFilterOption("s2", "Office", "Plex", 1, selected = true),
                ServerFilterOption("s3", "Empty", "ABS", 0, selected = false),
            ),
            options
        )
    }

    @Test
    fun `with no server filter every option is ticked`() {
        val options = serverFilterOptions(listOf(server("s1"), server("s2")), items, emptySet())
        assertTrue(options.all { it.selected })
    }

    @Test
    fun `a stale filter shows every option ticked`() {
        val options = serverFilterOptions(listOf(server("s1"), server("s2")), items, setOf("gone"))
        assertTrue(options.all { it.selected })
    }

    @Test
    fun `the chip names one server or counts several`() {
        val servers = listOf(server("s1", "Home server"), server("s2"), server("s3"))
        assertEquals("Home server", serverChipLabel(setOf("s1"), servers))
        assertEquals("2 servers", serverChipLabel(setOf("s1", "s2"), servers))
    }

    @Test
    fun `there is no chip without a filter - with one server - or with only stale ids`() {
        val servers = listOf(server("s1"), server("s2"))
        assertNull(serverChipLabel(emptySet(), servers))
        assertNull(serverChipLabel(setOf("s1"), listOf(server("s1"))))
        assertNull(serverChipLabel(setOf("gone"), servers))
    }

    // ---- the store ----

    @Test
    fun `the store keeps the server choice with the other filters`() {
        val store = BrowseQueryStore()
        store.update { it.copy(serverIds = setOf("s1")) }
        store.update { it.copy(sort = BrowseSort.TITLE) }

        assertEquals(BrowseQuery(sort = BrowseSort.TITLE, serverIds = setOf("s1")), store.query.value)
    }

    @Test
    fun `the store drops deleted servers from the saved choice`() {
        val store = BrowseQueryStore()
        store.update { it.copy(serverIds = setOf("s1", "s2")) }

        store.retainServers(setOf("s2", "s3"))
        assertEquals(setOf("s2"), store.query.value.serverIds)

        store.retainServers(setOf("s3"))
        assertEquals(emptySet(), store.query.value.serverIds)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a followed server list prunes the store when a server is deleted`() = runTest {
        val store = BrowseQueryStore()
        val servers = MutableStateFlow(setOf("s1", "s2"))
        store.followServers(servers, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        store.update { it.copy(serverIds = setOf("s1")) }
        assertEquals(setOf("s1"), store.query.value.serverIds)

        servers.value = setOf("s2")

        assertFalse("s1" in store.query.value.serverIds)
        assertEquals(emptySet(), store.query.value.serverIds)
    }

    @Test
    fun `with fewer than two servers left the server choice is dropped`() {
        val query = BrowseQuery(serverIds = setOf("s1"))
        assertEquals(emptySet(), pruneServerFilter(query, setOf("s1")).serverIds)
        assertEquals(emptySet(), pruneServerFilter(query, emptySet()).serverIds)
        assertFalse(isFiltering(pruneServerFilter(query, setOf("s1"))))
    }
}
