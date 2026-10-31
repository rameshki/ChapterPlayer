package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.playback.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSessionRulesTest {

    private val chapters = listOf(
        Chapter(0, "One", 0L, 60_000L),
        Chapter(1, "Two", 60_000L, 120_000L),
        Chapter(2, "Three", 120_000L, 180_000L),
    )

    private fun state(
        position: Long = 70_000L,
        playing: Boolean = false,
        playWhenReady: Boolean = playing,
        chapters: List<Chapter> = this.chapters,
        book: String? = "b",
        speed: Float = 1.0f,
    ) = PlaybackState(
        bookId = book, isPlaying = playing, positionMs = position, durationMs = 180_000L, speed = speed,
        chapterIndex = com.chapterplayer.domain.browse.currentChapterIndex(chapters, position),
        playWhenReady = playWhenReady, chapters = chapters,
    )

    @Test
    fun `an interruption resumes only a book that was playing and only when the system says so`() {
        assertEquals(AfterInterruption.RESUME, decideAfterInterruption(wasPlaying = true, shouldResume = true))
        assertEquals(AfterInterruption.STAY_PAUSED, decideAfterInterruption(wasPlaying = true, shouldResume = false))
        assertEquals(AfterInterruption.STAY_PAUSED, decideAfterInterruption(wasPlaying = false, shouldResume = true))
        assertEquals(AfterInterruption.STAY_PAUSED, decideAfterInterruption(wasPlaying = false, shouldResume = false))
    }

    @Test
    fun `headphones that go away pause a playing book and nothing else does`() {
        assertTrue(pausesOnRouteChange(RouteChange.OLD_DEVICE_UNAVAILABLE, playWhenReady = true))
        assertFalse(pausesOnRouteChange(RouteChange.OLD_DEVICE_UNAVAILABLE, playWhenReady = false))
        assertFalse(pausesOnRouteChange(RouteChange.NEW_DEVICE_AVAILABLE, playWhenReady = true))
        assertFalse(pausesOnRouteChange(RouteChange.OTHER, playWhenReady = true))
    }

    @Test
    fun `remote commands map to the player's actions`() {
        val s = state()
        assertEquals(RemoteAction.Play, remoteAction(RemoteCommand.Play, s))
        assertEquals(RemoteAction.Pause, remoteAction(RemoteCommand.Pause, s))
        assertEquals(RemoteAction.SkipForward, remoteAction(RemoteCommand.SkipForward, s))
        assertEquals(RemoteAction.SkipBack, remoteAction(RemoteCommand.SkipBack, s))
        assertEquals(RemoteAction.SeekTo(42_000L), remoteAction(RemoteCommand.ChangePosition(42_000L), s))
    }

    @Test
    fun `toggle pauses a playing or buffering book and plays a paused one`() {
        assertEquals(RemoteAction.Pause, remoteAction(RemoteCommand.TogglePlayPause, state(playing = true)))
        assertEquals(RemoteAction.Pause, remoteAction(RemoteCommand.TogglePlayPause, state(playing = false, playWhenReady = true)))
        assertEquals(RemoteAction.Play, remoteAction(RemoteCommand.TogglePlayPause, state(playing = false)))
    }

    @Test
    fun `without a loaded book every command does nothing`() {
        val none = state(book = null)
        listOf(
            RemoteCommand.Play, RemoteCommand.Pause, RemoteCommand.TogglePlayPause, RemoteCommand.SkipForward,
            RemoteCommand.SkipBack, RemoteCommand.ChangePosition(1L), RemoteCommand.NextChapter, RemoteCommand.PreviousChapter,
        ).forEach { assertEquals(RemoteAction.None, remoteAction(it, none)) }
    }

    @Test
    fun `a dragged position is kept inside the book`() {
        assertEquals(RemoteAction.SeekTo(0L), remoteAction(RemoteCommand.ChangePosition(-5L), state()))
        assertEquals(RemoteAction.SeekTo(180_000L), remoteAction(RemoteCommand.ChangePosition(999_999L), state()))
    }

    @Test
    fun `next and previous chapter jump like the player screen`() {
        assertEquals(RemoteAction.SeekTo(120_000L), remoteAction(RemoteCommand.NextChapter, state(position = 70_000L)))
        assertEquals(RemoteAction.None, remoteAction(RemoteCommand.NextChapter, state(position = 150_000L)))
        // Past the first 3 seconds of a chapter: back to its start; within them: the chapter before.
        assertEquals(RemoteAction.SeekTo(60_000L), remoteAction(RemoteCommand.PreviousChapter, state(position = 70_000L)))
        assertEquals(RemoteAction.SeekTo(0L), remoteAction(RemoteCommand.PreviousChapter, state(position = 61_000L)))
        assertEquals(RemoteAction.None, remoteAction(RemoteCommand.PreviousChapter, state(chapters = emptyList())))
    }

    @Test
    fun `the lock screen offers chapter jumps only with two chapters or more`() {
        assertEquals(RemoteCommandsEnabled(transport = true, chapters = true), remoteCommandsEnabled(state()))
        assertEquals(RemoteCommandsEnabled(transport = true, chapters = false), remoteCommandsEnabled(state(chapters = chapters.take(1))))
        assertEquals(RemoteCommandsEnabled(transport = false, chapters = false), remoteCommandsEnabled(state(book = null)))
    }

    @Test
    fun `the now playing card shows the book - its author - the chapter and the time in seconds`() {
        val info = nowPlayingInfo(state(position = 70_500L, playing = true, speed = 1.5f), "Book", "Author")!!
        assertEquals("Book", info.title)
        assertEquals("Author", info.artist)
        assertEquals("Two", info.chapterTitle)
        assertEquals(180.0, info.durationSec)
        assertEquals(70.5, info.elapsedSec)
        assertEquals(1.5, info.rate)
        assertEquals(1.5, info.defaultRate)
        assertEquals(1, info.chapterNumber)
        assertEquals(3, info.chapterCount)
    }

    @Test
    fun `a paused book has rate 0 on the card and keeps its speed as the default`() {
        val info = nowPlayingInfo(state(playing = false, speed = 2.0f), "Book", "Author")!!
        assertEquals(0.0, info.rate)
        assertEquals(2.0, info.defaultRate)
    }

    @Test
    fun `without chapters the card has no chapter and without a book there is no card`() {
        val info = nowPlayingInfo(state(chapters = emptyList()), "Book", "Author")!!
        assertNull(info.chapterTitle)
        assertNull(info.chapterNumber)
        assertEquals(0, info.chapterCount)
        assertNull(nowPlayingInfo(state(book = null), "Book", "Author"))
    }

    @Test
    fun `the card is set again on a change but not for ticks the system moves on itself`() {
        val shown = nowPlayingInfo(state(position = 70_000L, playing = true), "Book", "Author")!!
        // Two seconds later at 1x the system shows 72 s by itself.
        val ticked = nowPlayingInfo(state(position = 72_000L, playing = true), "Book", "Author")!!
        assertFalse(nowPlayingNeedsUpdate(shown, 1_000L, ticked, 3_000L))
        // A seek.
        val jumped = nowPlayingInfo(state(position = 100_000L, playing = true), "Book", "Author")!!
        assertTrue(nowPlayingNeedsUpdate(shown, 1_000L, jumped, 3_000L))
        // A pause (rate 0).
        val paused = nowPlayingInfo(state(position = 72_000L, playing = false), "Book", "Author")!!
        assertTrue(nowPlayingNeedsUpdate(shown, 1_000L, paused, 3_000L))
        // A new chapter, a new speed.
        val nextChapter = nowPlayingInfo(state(position = 121_000L, playing = true), "Book", "Author")!!
        assertTrue(nowPlayingNeedsUpdate(nowPlayingInfo(state(position = 119_000L, playing = true), "Book", "Author"), 0L, nextChapter, 2_000L))
        val faster = nowPlayingInfo(state(position = 72_000L, playing = true, speed = 2.0f), "Book", "Author")!!
        assertTrue(nowPlayingNeedsUpdate(shown, 1_000L, faster, 3_000L))
    }

    @Test
    fun `the card appears and is cleared when a book loads and goes`() {
        val info = nowPlayingInfo(state(), "Book", "Author")
        assertTrue(nowPlayingNeedsUpdate(null, 0L, info, 0L))
        assertTrue(nowPlayingNeedsUpdate(info, 0L, null, 0L))
        assertFalse(nowPlayingNeedsUpdate(null, 0L, null, 0L))
    }
}
