package com.chapterplayer.data.playback

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.playback.SleepTimer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NotYetOnIosPlaybackControllerTest {

    private val book = AudioBook(
        id = "b1", title = "Title", author = "Author", narrator = "", durationMs = 60_000L, chapters = emptyList(),
        streamUrl = "", coverUrl = null, source = ServerSource.ABS, serverId = "s1",
    )

    @Test
    fun `nothing is ever loaded so the mini player stays hidden`() {
        val controller = NotYetOnIosPlaybackController()
        assertEquals(PlaybackState(), controller.state.value)
        assertNull(controller.state.value.bookId)
        assertNull(controller.activeSession)
    }

    @Test
    fun `a start fails with the text the screens show from the server or the phone`() = runTest {
        val controller = NotYetOnIosPlaybackController()
        val fromServer = assertFailsWith<PlaybackStartException> { controller.load(book, 0L) }
        assertEquals("Playback is not available on iOS yet", fromServer.message)
        val fromPhone = assertFailsWith<PlaybackStartException> { controller.load(book, -1L, AudioOrigin.Phone(emptyList(), openSession = false)) }
        assertEquals(PLAYBACK_NOT_ON_IOS_TEXT, fromPhone.message)
        assertNull(controller.state.value.bookId)
    }

    @Test
    fun `the transport calls change nothing and never throw`() {
        val controller = NotYetOnIosPlaybackController()
        controller.play()
        controller.seekTo(5_000L)
        controller.setSpeed(1.5f)
        controller.skipBack()
        controller.skipForward()
        controller.seekToChapter(2)
        controller.pause()
        controller.releaseLocalFiles("b1")
        assertFalse(controller.state.value.isPlaying)
        assertEquals(PlaybackState(), controller.state.value)
        controller.release()
    }

    @Test
    fun `the sleep timer keeps what was chosen`() {
        val controller = NotYetOnIosPlaybackController()
        assertEquals(SleepTimer.Off, controller.sleepTimer.value)
        controller.setSleepTimer(SleepTimer.After(15))
        assertEquals(SleepTimer.After(15), controller.sleepTimer.value)
        controller.setSleepTimer(SleepTimer.Off)
        assertEquals(SleepTimer.Off, controller.sleepTimer.value)
    }
}
