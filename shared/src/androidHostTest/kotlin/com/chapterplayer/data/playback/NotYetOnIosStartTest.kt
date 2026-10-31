package com.chapterplayer.data.playback

import com.chapterplayer.data.PlatformFeatures
import com.chapterplayer.data.downloads.NoLocalBookFiles
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one start path (Home Resume, details, widgets) on iOS: a safe failure at once, nothing loaded, no server asked. */
class NotYetOnIosStartTest {

    private val book = testBook("a").copy(durationMs = 100_000L)

    @Test
    fun `without playback a start fails at once with the not-on-iOS text - online or offline - asking no server`() = runTest {
        for (online in listOf(true, false)) {
            val repo = FakeBookRepository(listOf(book))
            val controller = NotYetOnIosPlaybackController()
            val starter = PlaybackStarter(
                controller, repo, FakeAppSettings(), FakeConnectivity(online), NoLocalBookFiles, features = PlatformFeatures.IosBrowsingOnly
            )
            for (at in listOf(null, 5_000L)) {
                val error = starter.start(book, at).exceptionOrNull()
                assertIs<PlaybackStartException>(error)
                assertEquals("Playback is not available on iOS yet", error.message)
            }
            assertTrue(repo.fetchedProgress.isEmpty())
            assertNull(controller.state.value.bookId)
        }
    }

    @Test
    fun `the stand-in player alone also fails a start with the same text`() = runTest {
        val controller = NotYetOnIosPlaybackController()
        val starter = PlaybackStarter(controller, FakeBookRepository(listOf(book)), FakeAppSettings(), FakeConnectivity(), NoLocalBookFiles)
        val error = starter.start(book, 5_000L).exceptionOrNull()
        assertIs<PlaybackStartException>(error)
        assertEquals(PLAYBACK_NOT_ON_IOS_TEXT, error.message)
    }

    @Test
    fun `with playback the start goes on to the player`() = runTest {
        val controller = FakePlaybackController()
        val starter = PlaybackStarter(controller, FakeBookRepository(listOf(book)), FakeAppSettings(), FakeConnectivity(), NoLocalBookFiles, features = PlatformFeatures.Full)
        assertTrue(starter.start(book, 5_000L).isSuccess)
        assertEquals(listOf("a" to 5_000L), controller.loads)
    }
}
