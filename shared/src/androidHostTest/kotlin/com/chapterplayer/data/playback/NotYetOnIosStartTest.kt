package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.NoLocalBookFiles
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeConnectivity
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The one start path (Home Resume, details, widgets) over the iOS stand-ins: a safe failure, nothing loaded. */
class NotYetOnIosStartTest {

    private val book = testBook("a").copy(durationMs = 100_000L)
    private val controller = NotYetOnIosPlaybackController()
    private val starter = PlaybackStarter(controller, FakeBookRepository(listOf(book)), FakeAppSettings(), FakeConnectivity(), NoLocalBookFiles)

    @Test
    fun `a start fails with the not-on-iOS text, at a position or where listening left off`() = runTest {
        for (at in listOf(null, 5_000L)) {
            val result = starter.start(book, at)
            val error = result.exceptionOrNull()
            assertIs<PlaybackStartException>(error)
            assertEquals("Playback is not available on iOS yet", error.message)
            assertNull(controller.state.value.bookId)
        }
    }
}
