package com.chapterplayer.ui.stats

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportOutcome
import com.chapterplayer.data.stats.ImportedDay
import com.chapterplayer.data.stats.ListeningClock
import com.chapterplayer.data.stats.ListeningImport
import com.chapterplayer.data.stats.ListeningLogEntry
import com.chapterplayer.domain.stats.DayZone
import com.chapterplayer.domain.stats.MS_PER_DAY
import com.chapterplayer.domain.stats.StatsPeriod
import com.chapterplayer.domain.stats.TopKind
import com.chapterplayer.domain.stats.epochDayOf
import com.chapterplayer.testing.FakeBookRepository
import com.chapterplayer.testing.FakeDownloadOverview
import com.chapterplayer.testing.FakeDownloadStates
import com.chapterplayer.testing.FakeListeningLog
import com.chapterplayer.testing.FakeServerList
import com.chapterplayer.testing.testServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class StatsViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val today = epochDayOf(2026, 10, 26)

    private class FakeImport : ListeningImport {
        var gate: CompletableDeferred<ImportOutcome>? = null
        var refreshes = 0
        override fun start() {}
        override suspend fun refresh(): ImportOutcome {
            refreshes++
            return gate?.await() ?: ImportOutcome(imported = 1)
        }
    }

    private inner class Setup {
        val books = FakeBookRepository(listOf(AudioBook("abs_a", "A", "Ann Lee", "", 36_000_000L, emptyList(), "", null, ServerSource.ABS, "s1")))
        val log = FakeListeningLog()
        val importer = FakeImport()
        val servers = FakeServerList(listOf(testServer("s1")))
        var nowMs = today * MS_PER_DAY + 3_600_000L
        val vm = StatsViewModel(
            books, FakeDownloadStates(), FakeDownloadOverview(), log, servers, importer, DayZone.UTC,
            object : ListeningClock {
                override fun monoMs() = 0L
                override fun nowMs() = nowMs
            },
            Dispatchers.Main,
        )
    }

    @Test
    fun `the report follows the log and the chosen period`() = runTest {
        val s = Setup()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        assertEquals(StatsPeriod.MONTH, s.vm.uiState.value.period)
        assertTrue(s.vm.uiState.value.report!!.nothingYet)

        s.log.log.value = listOf(ListeningLogEntry(today, "abs_a", "s1", ServerSource.ABS, 600, 600), ListeningLogEntry(today - 30, "abs_a", "s1", ServerSource.ABS, 60, 60))
        assertEquals(600L, s.vm.uiState.value.report!!.listenedSeconds)
        s.vm.onPeriodSelected(StatsPeriod.ALL_TIME)
        assertEquals(660L, s.vm.uiState.value.report!!.listenedSeconds)
        assertEquals("Ann Lee", s.vm.uiState.value.report!!.top.getValue(TopKind.AUTHORS).single().name)
    }

    @Test
    fun `history imported from a server that was removed is not counted`() = runTest {
        val s = Setup()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        s.log.days.value = listOf(ImportedDay("s1", today, 100), ImportedDay("gone", today - 1, 900))
        assertEquals(100L, s.vm.uiState.value.report!!.listenedSeconds)
    }

    @Test
    fun `refresh imports again, shows progress and then the outcome`() = runTest {
        val s = Setup()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        val gate = CompletableDeferred<ImportOutcome>()
        s.importer.gate = gate
        s.vm.onRefresh()
        assertTrue(s.vm.uiState.value.refreshing)
        // A second tap while one runs does nothing.
        s.vm.onRefresh()
        gate.complete(ImportOutcome(imported = 1))
        assertFalse(s.vm.uiState.value.refreshing)
        assertEquals(1, s.importer.refreshes)
        assertEquals("History updated from 1 server", s.vm.uiState.value.message)
        s.vm.onMessageShown()
        assertNull(s.vm.uiState.value.message)
    }

    @Test
    fun `an open page moves to the new day after midnight`() = runTest {
        val s = Setup()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        s.vm.onPeriodSelected(StatsPeriod.WEEK)
        assertEquals(today, s.vm.uiState.value.report!!.range.last)
        s.log.log.value = listOf(ListeningLogEntry(today + 1, "abs_a", "s1", ServerSource.ABS, 600, 600))
        // The clock passes midnight; within the minute the page notices.
        s.nowMs = (today + 1) * MS_PER_DAY + 60_000L
        advanceTimeBy(60_001)
        assertEquals(today + 1, s.vm.uiState.value.report!!.range.last)
        assertEquals(600L, s.vm.uiState.value.report!!.listenedSeconds)
    }

    @Test
    fun `the top list kind can be switched`() = runTest {
        val s = Setup()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.vm.uiState.collect {} }
        s.vm.onTopKindSelected(TopKind.SERIES)
        assertEquals(TopKind.SERIES, s.vm.uiState.value.topKind)
    }
}
