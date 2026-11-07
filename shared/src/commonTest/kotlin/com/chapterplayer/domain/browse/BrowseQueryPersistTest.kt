package com.chapterplayer.domain.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseQueryPersistTest {

    private val stored = BrowseQuery(
        progress = ProgressFilter.FINISHED,
        sort = BrowseSort.TITLE,
        serverIds = setOf("s1", "s2"),
        favouritesOnly = true,
    )

    @Test
    fun `the stored filters come back at start`() = runTest {
        val store = BrowseQueryStore()
        store.persist(flowOf(encodeBrowseQuery(stored)), save = {}, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertEquals(stored, store.query.value)
    }

    @Test
    fun `nothing stored or garbage leaves the default filters`() = runTest {
        for (value in listOf(null, "{broken", "[]")) {
            val store = BrowseQueryStore()
            store.persist(flowOf(value), save = {}, backgroundScope, UnconfinedTestDispatcher(testScheduler))
            assertEquals(BrowseQuery(), store.query.value, "for $value")
        }
    }

    @Test
    fun `a settings read that fails leaves the default filters`() = runTest {
        val store = BrowseQueryStore()
        val failing = flow<String?> { throw IllegalStateException("storage unavailable") }
        store.persist(failing, save = {}, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertEquals(BrowseQuery(), store.query.value)
    }

    @Test
    fun `every change is saved and an unchanged one is not saved again`() = runTest {
        val saves = mutableListOf<String>()
        val store = BrowseQueryStore()
        store.persist(flowOf(encodeBrowseQuery(stored)), save = { saves += it }, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        assertEquals(emptyList(), saves, "restoring writes nothing back")

        store.update { it.copy(download = DownloadFilter.DOWNLOADED) }
        store.update { it.copy(download = DownloadFilter.DOWNLOADED) }

        assertEquals(listOf(encodeBrowseQuery(stored.copy(download = DownloadFilter.DOWNLOADED))), saves)
    }

    @Test
    fun `clearing the filters saves the default query`() = runTest {
        val saves = mutableListOf<String>()
        val store = BrowseQueryStore()
        store.persist(flowOf(encodeBrowseQuery(stored)), save = { saves += it }, backgroundScope, UnconfinedTestDispatcher(testScheduler))

        store.update { BrowseQuery() }

        assertEquals(BrowseQuery(), decodeBrowseQuery(saves.last()))
    }

    @Test
    fun `a choice made before the stored filters were read is kept`() = runTest {
        val read = CompletableDeferred<String?>()
        val store = BrowseQueryStore()
        store.persist(flow { emit(read.await()) }, save = {}, backgroundScope, UnconfinedTestDispatcher(testScheduler))

        store.update { it.copy(sort = BrowseSort.LENGTH) }
        read.complete(encodeBrowseQuery(stored))

        assertEquals(BrowseQuery(sort = BrowseSort.LENGTH), store.query.value)
    }

    @Test
    fun `a failing save is not fatal and the next change is saved`() = runTest {
        var fail = true
        val saves = mutableListOf<String>()
        val store = BrowseQueryStore()
        store.persist(flowOf(null), save = { if (fail) error("disk full") else saves += it }, backgroundScope, UnconfinedTestDispatcher(testScheduler))

        store.update { it.copy(sort = BrowseSort.TITLE) }
        fail = false
        store.update { it.copy(sort = BrowseSort.AUTHOR) }

        assertEquals(listOf(encodeBrowseQuery(BrowseQuery(sort = BrowseSort.AUTHOR))), saves)
    }

    // ---- the lists wait for the restore ----

    @Test
    fun `a store that is not persisted gives its query at once`() = runTest {
        val store = BrowseQueryStore()
        assertEquals(BrowseQuery(), withTimeoutOrNull(1) { store.settledQuery.first() })
    }

    @Test
    fun `the settled query waits for the stored filters and never gives the defaults first`() = runTest {
        val read = CompletableDeferred<String?>()
        val store = BrowseQueryStore()
        store.persist(flow { emit(read.await()) }, save = {}, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<BrowseQuery>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.settledQuery.collect { seen += it } }
        assertEquals(emptyList(), seen)

        read.complete(encodeBrowseQuery(stored))

        assertEquals(listOf(stored), seen)
    }

    @Test
    fun `a stored read that never answers gives the defaults after a short wait`() = runTest {
        val store = BrowseQueryStore()
        store.persist(flow { awaitCancellation() }, save = {}, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<BrowseQuery>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.settledQuery.collect { seen += it } }
        assertEquals(emptyList(), seen)

        advanceTimeBy(BROWSE_RESTORE_WAIT_MS + 1)

        assertEquals(listOf(BrowseQuery()), seen)
        store.update { it.copy(sort = BrowseSort.TITLE) }
        assertEquals(BrowseQuery(sort = BrowseSort.TITLE), seen.last())
    }

    // ---- stored server ids and the server list ----

    @Test
    fun `stored servers that no longer exist are dropped when the server list is already known`() = runTest {
        val store = BrowseQueryStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        store.followServers(MutableStateFlow(setOf("s1", "s3")), backgroundScope, dispatcher)
        store.persist(flowOf(encodeBrowseQuery(stored)), save = {}, backgroundScope, dispatcher)

        assertEquals(setOf("s1"), store.query.value.serverIds)
        assertEquals(stored.copy(serverIds = setOf("s1")), store.query.value)
    }

    @Test
    fun `stored servers that no longer exist are dropped when the server list arrives later`() = runTest {
        val store = BrowseQueryStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        store.persist(flowOf(encodeBrowseQuery(stored.copy(serverIds = setOf("s1", "gone")))), save = {}, backgroundScope, dispatcher)
        assertEquals(setOf("s1", "gone"), store.query.value.serverIds)

        store.retainServers(setOf("s1", "s2"))

        assertEquals(setOf("s1"), store.query.value.serverIds)
    }

    @Test
    fun `a stored server choice is dropped when fewer than two servers remain`() = runTest {
        val store = BrowseQueryStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        store.followServers(MutableStateFlow(setOf("s1")), backgroundScope, dispatcher)
        store.persist(flowOf(encodeBrowseQuery(stored)), save = {}, backgroundScope, dispatcher)

        assertEquals(emptySet(), store.query.value.serverIds)
    }
}
