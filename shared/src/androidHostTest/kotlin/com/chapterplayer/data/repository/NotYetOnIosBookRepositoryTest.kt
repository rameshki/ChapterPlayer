package com.chapterplayer.data.repository

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.sync.DataStoreServerStatusStore
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.data.sync.ServerSyncPhase
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS stand-in for the book repository (Stage 5 step 1): empty lists, and every server action says it is not there yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotYetOnIosBookRepositoryTest {

    private val repo = NotYetOnIosBookRepository
    private val server = ServerConfig("a", ServerSource.ABS, "Home", "http://a.test", "t")

    @Test
    fun `every list is empty`() = runTest {
        assertTrue(repo.observeAllBooks().first().isEmpty())
        assertTrue(repo.observeBooksByServer("a").first().isEmpty())
        assertTrue(repo.observeCollections().first().isEmpty())
        assertTrue(repo.observeLibraries().first().isEmpty())
        assertTrue(repo.observeBookmarks("b").first().isEmpty())
        assertTrue(repo.observePendingBookmarks().first().isEmpty())
        assertTrue(repo.observeHistory("b").first().isEmpty())
        assertTrue(repo.observeInProgressBooks().first().isEmpty())
        assertTrue(repo.observeAllProgress().first().isEmpty())
        assertNull(repo.observeProgress("b").first())
        assertTrue(repo.pendingProgress().isEmpty())
    }

    @Test
    fun `server actions fail with the not-yet text`() = runTest {
        val results = listOf(
            repo.syncBooksFromServer(server), repo.loadDetails("b"), repo.startPlayback("b"), repo.fetchServerProgress("b"),
            repo.pullBookmarks("a"), repo.pushPendingBookmarks("a"), repo.markFinished("b", true),
        )
        results.forEach { result ->
            assertEquals(NOT_YET_ON_IOS_TEXT, result.exceptionOrNull()?.message)
        }
        assertFailsWith<UnsupportedOperationException> { repo.addBookmark("b", 1_000L, null) }
    }

    @Test
    fun `Sync now on the Servers page reads as not available on iOS yet`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val sync = LibrarySyncCoordinator(
            repo, FakeServerList(listOf(server)), scope,
            statusStore = DataStoreServerStatusStore(InMemoryPreferencesDataStore()), nowMs = { 1_000L },
        )
        scope.launch { sync.serverStatus.collect {} }

        sync.syncNow("a")

        assertEquals(ServerSyncPhase.Failed(NOT_YET_ON_IOS_TEXT, 1_000L), sync.serverStatus.value.getValue("a").phase)
    }
}
