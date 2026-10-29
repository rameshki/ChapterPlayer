package com.chapterplayer.data.playback

import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakeLocalBookFiles
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a merged book starts: the newest progress of any of its copies, mapped by fraction. */
class PlaybackStarterMirrorTest {

    private val book = testBook("a").copy(durationMs = 100_000L)
    private val controller = FakePlaybackController()
    private val repo = FakeBookRepository(listOf(book, testBook("p").copy(durationMs = 120_000L)))
    private val connectivity = FakeConnectivity()
    private val peers = object : ProgressMirrorPeers {
        var copies = listOf(MirrorPeer("p", "plex_srv", 120_000L))
        override suspend fun peersOf(bookId: String): List<MirrorPeer> = if (bookId == "a") copies else emptyList()
    }
    private val starter = PlaybackStarter(controller, repo, FakeAppSettings(), connectivity, FakeLocalBookFiles(), peers)

    private fun serverProgress(vararg byBook: Pair<String, ServerProgress>) {
        val map = byBook.toMap()
        repo.onFetchServerProgress = { id -> Result.success(map[id]) }
    }

    @Test
    fun `newer listening on the other copy on this phone is found and mapped`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L), ReadingProgress("p", 90_000L, 120_000L, 5L))
        starter.start(book)
        assertEquals(listOf("a" to 75_000L), controller.loads)
    }

    @Test
    fun `newer progress on the other copy's server, from another device, is found and mapped`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L))
        serverProgress("p" to ServerProgress(60_000L, 120_000L, false, 9L))
        starter.start(book)
        assertEquals(listOf("a" to 50_000L), controller.loads)
        assertTrue("p" in repo.fetchedProgress)
    }

    @Test
    fun `this copy's newer progress wins`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 9L), ReadingProgress("p", 90_000L, 120_000L, 5L))
        serverProgress("p" to ServerProgress(60_000L, 120_000L, false, 8L))
        starter.start(book)
        assertEquals(listOf("a" to 40_000L), controller.loads)
    }

    @Test
    fun `a mirrored row of the other copy is not news`() = runTest {
        repo.progress.value = listOf(
            ReadingProgress("a", 40_000L, 100_000L, 1L), ReadingProgress("p", 90_000L, 120_000L, 5L, mirrored = true)
        )
        starter.start(book)
        assertEquals(listOf("a" to 40_000L), controller.loads)
    }

    @Test
    fun `the other copy finished more recently starts this one over`() = runTest {
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L), ReadingProgress("p", 120_000L, 120_000L, 5L, isFinished = true))
        starter.start(book)
        assertEquals(listOf("a" to 0L), controller.loads)
    }

    @Test
    fun `offline the other copy's server is not asked, its row here still counts`() = runTest {
        connectivity.online.value = false
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L), ReadingProgress("p", 60_000L, 120_000L, 5L))
        starter.start(book) // not downloaded and offline: it cannot play
        assertTrue(repo.fetchedProgress.isEmpty())
    }

    @Test
    fun `with mirroring off only this copy counts`() = runTest {
        peers.copies = emptyList()
        repo.progress.value = listOf(ReadingProgress("a", 40_000L, 100_000L, 1L), ReadingProgress("p", 90_000L, 120_000L, 5L))
        serverProgress("p" to ServerProgress(60_000L, 120_000L, false, 9L))
        starter.start(book)
        assertEquals(listOf("a" to 40_000L), controller.loads)
        assertFalse("p" in repo.fetchedProgress)
    }
}
