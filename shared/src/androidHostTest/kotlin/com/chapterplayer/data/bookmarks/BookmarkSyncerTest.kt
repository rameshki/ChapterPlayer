package com.chapterplayer.data.bookmarks

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.playback.Bookmark
import com.chapterplayer.domain.playback.BookmarkState
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeServerList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkSyncerTest {

    private class Rig(scope: CoroutineScope, now: () -> Long, online: Boolean, servers: List<ServerConfig>) {
        val repo = FakeBookRepository()
        val connectivity = FakeConnectivity(online)
        val servers = FakeServerList(servers)
        val syncer = BookmarkSyncer(repo, this.servers, connectivity, scope, now)
    }

    private fun server(id: String, source: ServerSource = ServerSource.ABS) =
        ServerConfig(id = id, source = source, name = id, baseUrl = "http://$id.test", token = "t")

    private fun TestScope.rig(online: Boolean = true, vararg servers: ServerConfig) = Rig(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        { testScheduler.currentTime },
        online,
        servers.toList()
    )

    private fun pendingBookmark(book: String = "abs_x", at: Long = 10) =
        Bookmark(book, at, "Mark", 1L, BookmarkState.PENDING_CREATE)

    @Test
    fun `a pass pulls and then pushes each Audiobookshelf server and skips Plex`() = runTest {
        val r = rig(true, server("a"), server("p", ServerSource.PLEX), server("b"))
        r.syncer.start()
        runCurrent()
        assertEquals(listOf("pull a", "push a", "pull b", "push b"), r.repo.bookmarkCalls)
    }

    @Test
    fun `nothing happens while offline and the pass runs when the network returns`() = runTest {
        val r = rig(false, server("a"))
        r.syncer.start()
        runCurrent()
        assertTrue(r.repo.bookmarkCalls.isEmpty())
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("pull a", "push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `coming online again is throttled to one pull per five minutes per server`() = runTest {
        val r = rig(true, server("a"))
        r.syncer.start()
        runCurrent()
        advanceTimeBy(60_000)
        r.connectivity.online.value = false
        runCurrent()
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("pull a", "push a"), r.repo.bookmarkCalls)
        advanceTimeBy(250_000)
        r.connectivity.online.value = false
        runCurrent()
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("pull a", "push a", "pull a", "push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a throttled online event still pushes what waits locally but does not pull`() = runTest {
        val r = rig(false, server("a"))
        r.syncer.start()
        r.repo.bookmarks.value = listOf(pendingBookmark())
        advanceTimeBy(10_000)
        assertTrue(r.repo.bookmarkCalls.isEmpty())
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("pull a", "push a"), r.repo.bookmarkCalls)
        r.connectivity.online.value = false
        runCurrent()
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("pull a", "push a", "push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a local change waits three seconds and then only pushes`() = runTest {
        val r = rig(true, server("a"))
        r.syncer.start()
        runCurrent()
        r.repo.bookmarkCalls.clear()
        r.repo.bookmarks.value = listOf(pendingBookmark())
        advanceTimeBy(2_999)
        assertTrue(r.repo.bookmarkCalls.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a second local change restarts the wait`() = runTest {
        val r = rig(true, server("a"))
        r.syncer.start()
        runCurrent()
        r.repo.bookmarkCalls.clear()
        r.repo.bookmarks.value = listOf(pendingBookmark(at = 10))
        advanceTimeBy(2_000)
        r.repo.bookmarks.value = listOf(pendingBookmark(at = 10), pendingBookmark(at = 20))
        advanceTimeBy(2_000)
        assertTrue(r.repo.bookmarkCalls.isEmpty())
        advanceTimeBy(1_100)
        assertEquals(listOf("push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a local change while offline waits for the network`() = runTest {
        val r = rig(false, server("a"))
        r.syncer.start()
        runCurrent()
        r.repo.bookmarks.value = listOf(pendingBookmark())
        advanceTimeBy(60_000)
        assertTrue(r.repo.bookmarkCalls.isEmpty())
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(listOf("pull a", "push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a failure retries with the backoff and a success resets the count`() = runTest {
        val r = rig(true, server("a"))
        var failing = true
        r.repo.onPullBookmarks = { if (failing) Result.failure(RuntimeException("down")) else Result.success(Unit) }
        r.syncer.start()
        runCurrent()
        assertEquals(1, r.repo.pulledBookmarks.size)
        advanceTimeBy(4_999)
        assertEquals(1, r.repo.pulledBookmarks.size)
        advanceTimeBy(2)
        assertEquals(2, r.repo.pulledBookmarks.size)
        // The second failure waits longer (10 s).
        advanceTimeBy(9_000)
        assertEquals(2, r.repo.pulledBookmarks.size)
        failing = false
        advanceTimeBy(1_500)
        assertEquals(3, r.repo.pulledBookmarks.size)
        // Success: no more retries, and the next failure starts from the first wait again.
        advanceTimeBy(600_000)
        assertEquals(3, r.repo.pulledBookmarks.size)
        failing = true
        r.connectivity.online.value = false
        runCurrent()
        r.connectivity.online.value = true
        runCurrent()
        assertEquals(4, r.repo.pulledBookmarks.size)
        advanceTimeBy(5_001)
        assertEquals(5, r.repo.pulledBookmarks.size)
    }

    @Test
    fun `a failed push retries too`() = runTest {
        val r = rig(true, server("a"))
        r.repo.onPushBookmarks = { Result.failure(RuntimeException("down")) }
        r.syncer.start()
        runCurrent()
        assertEquals(listOf("pull a", "push a"), r.repo.bookmarkCalls)
        advanceTimeBy(5_001)
        assertEquals(listOf("pull a", "push a", "pull a", "push a"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a refused sign-in skips that server only and schedules no retry`() = runTest {
        val r = rig(true, server("a"), server("b"))
        r.repo.onPullBookmarks = { id -> if (id == "a") Result.failure(SignInRequiredException("a")) else Result.success(Unit) }
        r.syncer.start()
        runCurrent()
        assertEquals(listOf("pull a", "pull b", "push b"), r.repo.bookmarkCalls)
        advanceTimeBy(600_000)
        assertEquals(listOf("pull a", "pull b", "push b"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a refused sign-in on the push skips the server without a retry`() = runTest {
        val r = rig(true, server("a"), server("b"))
        r.repo.onPushBookmarks = { id -> if (id == "a") Result.failure(SignInRequiredException("a")) else Result.success(Unit) }
        r.syncer.start()
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(listOf("pull a", "push a", "pull b", "push b"), r.repo.bookmarkCalls)
    }

    @Test
    fun `a failing or throwing server does not stop the others`() = runTest {
        val r = rig(true, server("a"), server("b"), server("c"))
        r.repo.onPullBookmarks = { id ->
            when (id) {
                "a" -> error("boom")
                "b" -> Result.failure(RuntimeException("down"))
                else -> Result.success(Unit)
            }
        }
        r.syncer.start()
        runCurrent()
        assertEquals(listOf("pull a", "push a", "pull b", "push b", "pull c", "push c"), r.repo.bookmarkCalls)
    }

    @Test
    fun `starting twice runs one collector`() = runTest {
        val r = rig(true, server("a"))
        r.syncer.start()
        r.syncer.start()
        runCurrent()
        assertEquals(listOf("pull a", "push a"), r.repo.bookmarkCalls)
    }
}
