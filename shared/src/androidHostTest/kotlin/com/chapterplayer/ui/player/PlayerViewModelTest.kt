package com.chapterplayer.ui.player

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.testing.FakeAppSettings
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakePlaybackController
import com.chapterplayer.testing.testBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // Three chapters of 10 s each: 0-10, 10-20, 20-30 s.
    private val chapters = listOf(
        Chapter(0, "One", 0L, 10_000L),
        Chapter(1, "Two", 10_000L, 20_000L),
        Chapter(2, "Three", 20_000L, 30_000L)
    )
    private val book = testBook("a", title = "A Title", author = "An Author")
        .copy(durationMs = 30_000L, chapters = chapters, coverUrl = "http://cover.test/a.jpg")

    private class Setup(val book: AudioBook) {
        val controller = FakePlaybackController()
        val repo = FakeBookRepository(listOf(book))
        val settings = FakeAppSettings()
        val vm = PlayerViewModel(
            controller, repo, settings, Dispatchers.Main
        )
    }

    private fun TestScope.collect(s: Setup): () -> PlayerUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        return { s.vm.uiState.value }
    }

    private fun playing(
        position: Long = 0L,
        chapterIndex: Int? = 0,
        withChapters: List<Chapter> = chapters,
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        playWhenReady: Boolean = false,
        duration: Long = 30_000L
    ) = PlaybackState(
        bookId = "a", positionMs = position, durationMs = duration, chapterIndex = chapterIndex,
        chapters = withChapters, isPlaying = isPlaying, isBuffering = isBuffering, playWhenReady = playWhenReady
    )

    @Test
    fun `nothing playing is the starting state`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        assertTrue(state().nothingPlaying)
        assertEquals("", state().title)
        assertTrue(state().chapters.isEmpty())
        assertFalse(state().canGoNext)
    }

    @Test
    fun `the state shows the book, chapter, fractions and labels`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        s.controller.playback.value = playing(position = 15_000L, chapterIndex = 1, isPlaying = true).copy(speed = 1.25f)

        val ui = state()
        assertFalse(ui.nothingPlaying)
        assertEquals("A Title", ui.title)
        assertEquals("An Author", ui.author)
        assertEquals("http://cover.test/a.jpg", ui.coverUrl)
        assertEquals("Two", ui.chapterTitle)
        assertEquals(0.5f, ui.bookFraction)
        assertEquals(0.5f, ui.chapterFraction)
        assertEquals("0:15", ui.elapsed)
        assertEquals("0:15", ui.remaining)
        assertEquals("0:05", ui.chapterElapsed)
        assertEquals("0:05", ui.chapterRemaining)
        assertEquals("under 1 min left", ui.timeLeft)
        assertTrue(ui.isPlaying)
        assertEquals(1.25f, ui.speed)
        assertEquals("1.25x", ui.speedLabel)
        assertEquals(listOf(false, true, false), ui.chapters.map { it.isCurrent })
        assertEquals(1, ui.currentChapterIndex)
        assertTrue(ui.canGoPrevious)
        assertTrue(ui.canGoNext)
        assertNull(ui.error)
    }

    @Test
    fun `the current chapter follows the live state`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        s.controller.playback.value = playing(position = 1_000L, chapterIndex = 0)
        assertEquals(listOf(true, false, false), state().chapters.map { it.isCurrent })
        s.controller.playback.value = playing(position = 25_000L, chapterIndex = 2)
        assertEquals(listOf(false, false, true), state().chapters.map { it.isCurrent })
        assertFalse(state().canGoNext)
    }

    @Test
    fun `a book without chapters has no chapter fields`() = runTest {
        val s = Setup(book.copy(chapters = emptyList()))
        val state = collect(s)
        s.controller.playback.value = playing(position = 5_000L, chapterIndex = null, withChapters = emptyList())

        assertNull(state().chapterTitle)
        assertNull(state().chapterFraction)
        assertNull(state().chapterElapsed)
        assertNull(state().currentChapterIndex)
        assertFalse(state().canGoPrevious)
        assertFalse(state().canGoNext)
    }

    @Test
    fun `buffering and an unknown length are shown safely`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        s.controller.playback.value = playing(isBuffering = true, playWhenReady = true, duration = 0L)
        assertTrue(state().isBuffering)
        assertEquals(0f, state().bookFraction)
        assertEquals("", state().timeLeft)
    }

    @Test
    fun `the sleep timer and its countdown show in the label`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        assertEquals("Sleep timer", state().sleepLabel)

        s.controller.playback.value = playing().copy(sleepRemainingMs = 65_000L)
        s.controller.sleep.value = SleepTimer.After(15)
        assertEquals(SleepTimer.After(15), state().sleepTimer)
        assertEquals("1:05", state().sleepLabel)

        s.controller.sleep.value = SleepTimer.EndOfChapter
        assertEquals("End of chapter", state().sleepLabel)
    }

    @Test
    fun `play pause toggles by state and does nothing when nothing is loaded`() = runTest {
        val s = Setup(book)
        collect(s)
        s.vm.playPause()
        assertEquals(0, s.controller.plays + s.controller.pauses)

        s.controller.playback.value = playing()
        s.vm.playPause()
        assertEquals(1, s.controller.plays)

        s.controller.playback.value = playing(isPlaying = true)
        s.vm.playPause()
        assertEquals(1, s.controller.pauses)

        s.controller.playback.value = playing(isBuffering = true, playWhenReady = true)
        s.vm.playPause()
        assertEquals(2, s.controller.pauses)

        // Buffering without a wish to play (a seek while paused) means play.
        s.controller.playback.value = playing(isBuffering = true, playWhenReady = false)
        s.vm.playPause()
        assertEquals(2, s.controller.plays)
    }

    @Test
    fun `skips go to the controller`() = runTest {
        val s = Setup(book)
        collect(s)
        s.vm.skipBack()
        s.vm.skipForward()
        s.vm.skipForward()
        assertEquals(1, s.controller.skipBacks)
        assertEquals(2, s.controller.skipForwards)
    }

    @Test
    fun `previous chapter restarts the chapter after 3 seconds, else goes to the one before`() = runTest {
        val s = Setup(book)
        collect(s)
        s.controller.playback.value = playing(position = 15_000L, chapterIndex = 1)
        s.vm.previousChapter()
        s.controller.playback.value = playing(position = 11_000L, chapterIndex = 1)
        s.vm.previousChapter()
        s.controller.playback.value = playing(position = 1_000L, chapterIndex = 0)
        s.vm.previousChapter()
        assertEquals(listOf(10_000L, 0L, 0L), s.controller.seeks)
    }

    @Test
    fun `previous chapter with no chapters goes to the start of the book, and with nothing loaded does nothing`() = runTest {
        val s = Setup(book)
        collect(s)
        s.vm.previousChapter()
        assertTrue(s.controller.seeks.isEmpty())
        s.controller.playback.value = playing(position = 5_000L, chapterIndex = null, withChapters = emptyList())
        s.vm.previousChapter()
        assertEquals(listOf(0L), s.controller.seeks)
    }

    @Test
    fun `next chapter goes to the next start and does nothing on the last or without chapters`() = runTest {
        val s = Setup(book)
        collect(s)
        s.controller.playback.value = playing(position = 5_000L, chapterIndex = 0)
        s.vm.nextChapter()
        s.controller.playback.value = playing(position = 25_000L, chapterIndex = 2)
        s.vm.nextChapter()
        s.controller.playback.value = playing(position = 5_000L, chapterIndex = null, withChapters = emptyList())
        s.vm.nextChapter()
        assertEquals(listOf(10_000L), s.controller.seeks)
    }

    @Test
    fun `seek book maps the fraction onto the duration and clamps`() = runTest {
        val s = Setup(book)
        collect(s)
        s.controller.playback.value = playing()
        s.vm.seekBook(0.5f)
        s.vm.seekBook(-1f)
        s.vm.seekBook(7f)
        s.vm.seekBook(Float.NaN)
        assertEquals(listOf(15_000L, 0L, 30_000L), s.controller.seeks)
    }

    @Test
    fun `seek book with a duration of zero seeks to zero, and with nothing loaded does nothing`() = runTest {
        val s = Setup(book)
        collect(s)
        s.vm.seekBook(0.5f)
        assertTrue(s.controller.seeks.isEmpty())

        s.controller.playback.value = playing(duration = 0L)
        s.vm.seekBook(0.5f)
        assertEquals(listOf(0L), s.controller.seeks)
    }

    @Test
    fun `seek chapter stays inside the current chapter and clamps`() = runTest {
        val s = Setup(book)
        collect(s)
        s.controller.playback.value = playing(position = 12_000L, chapterIndex = 1)
        s.vm.seekChapter(0.25f)
        s.vm.seekChapter(-3f)
        s.vm.seekChapter(3f)
        // The very end is the last moment of the chapter, not the start of the next one.
        assertEquals(listOf(12_500L, 10_000L, 19_999L), s.controller.seeks)
    }

    @Test
    fun `seek chapter does nothing without chapters`() = runTest {
        val s = Setup(book)
        collect(s)
        s.controller.playback.value = playing(chapterIndex = null, withChapters = emptyList())
        s.vm.seekChapter(0.5f)
        assertTrue(s.controller.seeks.isEmpty())
    }

    @Test
    fun `jump to chapter seeks to its start and ignores a bad index`() = runTest {
        val s = Setup(book)
        collect(s)
        s.controller.playback.value = playing()
        s.vm.jumpToChapter(2)
        s.vm.jumpToChapter(3)
        s.vm.jumpToChapter(-1)
        assertEquals(listOf(20_000L), s.controller.seeks)
    }

    @Test
    fun `speed goes to the controller and is saved, clamped to the supported range`() = runTest {
        val s = Setup(book)
        collect(s)
        s.vm.setSpeed(1.5f)
        assertEquals(listOf(1.5f), s.controller.speeds)
        assertEquals(1.5f, s.settings.speed.value)

        s.vm.setSpeed(10f)
        assertEquals(3.0f, s.controller.speeds.last())
        assertEquals(3.0f, s.settings.speed.value)

        s.vm.setSpeed(Float.NaN)
        assertEquals(2, s.controller.speeds.size)
    }

    @Test
    fun `a settings failure still changes the speed`() = runTest {
        val s = Setup(book)
        val failing = object : AppSettings by s.settings {
            override suspend fun setPlaybackSpeed(speed: Float) { error("storage unavailable") }
        }
        val vm = PlayerViewModel(s.controller, s.repo, failing, Dispatchers.Main)
        vm.setSpeed(2f)
        assertEquals(listOf(2f), s.controller.speeds)
    }

    @Test
    fun `the sleep timer is passed to the controller`() = runTest {
        val s = Setup(book)
        collect(s)
        s.vm.setSleepTimer(SleepTimer.After(30))
        s.vm.setSleepTimer(SleepTimer.Off)
        assertEquals(listOf(SleepTimer.After(30), SleepTimer.Off), s.controller.sleepSets)
    }

    @Test
    fun `a dismissed error stays hidden until its text changes or clears`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        s.controller.playback.value = playing().copy(error = "Playback stopped")
        assertEquals("Playback stopped", state().error)

        s.vm.dismissError()
        assertNull(state().error)

        // The same text again after other changes stays hidden.
        s.controller.playback.value = playing(position = 1_000L).copy(error = "Playback stopped")
        assertNull(state().error)

        // A different text shows.
        s.controller.playback.value = playing().copy(error = "Lost the connection")
        assertEquals("Lost the connection", state().error)

        // Once cleared, the old text can show again.
        s.vm.dismissError()
        s.controller.playback.value = playing()
        s.controller.playback.value = playing().copy(error = "Lost the connection")
        assertEquals("Lost the connection", state().error)
    }

    @Test
    fun `a loaded book missing from the cache shows a fallback title`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        s.controller.playback.value = playing().copy(bookId = "gone")
        assertEquals("Now playing", state().title)
        assertEquals("", state().author)
        assertFalse(state().nothingPlaying)
    }

    @Test
    fun `the live chapter index is the one source, the position only fills in when it is null`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        // Exactly on the boundary the position says chapter 2 but the player says chapter 1: the player wins.
        s.controller.playback.value = playing(position = 20_000L, chapterIndex = 1)
        assertEquals("Two", state().chapterTitle)
        assertEquals(1, state().currentChapterIndex)
        assertEquals(1f, state().chapterFraction)
        assertTrue(state().canGoNext)
        s.vm.seekChapter(0.5f)
        s.vm.nextChapter()
        assertEquals(listOf(15_000L, 20_000L), s.controller.seeks)

        // No live index: the position decides.
        s.controller.playback.value = playing(position = 12_000L, chapterIndex = null)
        assertEquals("Two", state().chapterTitle)
        assertEquals(1, state().currentChapterIndex)
        assertEquals(listOf(false, true, false), state().chapters.map { it.isCurrent })
        assertTrue(state().canGoPrevious)
        assertTrue(state().canGoNext)
    }

    @Test
    fun `show pause is playing or buffering with playback asked for`() = runTest {
        val s = Setup(book)
        val state = collect(s)
        val cases = listOf(
            Triple(false, false, false) to false,
            Triple(true, false, false) to true,
            Triple(true, true, true) to true,
            Triple(false, true, true) to true,
            Triple(false, true, false) to false,
            Triple(false, false, true) to false,
        )
        for ((flags, expected) in cases) {
            val (isPlaying, isBuffering, wanted) = flags
            s.controller.playback.value = playing(isPlaying = isPlaying, isBuffering = isBuffering, playWhenReady = wanted)
            assertEquals(expected, state().showPause, "playing=$isPlaying buffering=$isBuffering wanted=$wanted")
            assertEquals(isBuffering, state().isBuffering)
        }
    }

    @Test
    fun `a new view model over a playing controller shows the book from the first state`() {
        val s = Setup(book)
        s.controller.playback.value = playing(position = 12_000L, chapterIndex = 1, isPlaying = true)

        // A second view model, as when a screen opens: no collector, no flash of "nothing playing".
        val fresh = PlayerViewModel(s.controller, s.repo, s.settings, Dispatchers.Main)

        assertFalse(fresh.uiState.value.nothingPlaying)
        assertEquals("Two", fresh.uiState.value.chapterTitle)
        assertTrue(fresh.uiState.value.showPause)
    }
}
