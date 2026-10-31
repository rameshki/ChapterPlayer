package com.chapterplayer.domain.stats

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ReadingProgress
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportedDay
import com.chapterplayer.data.stats.ImportedItem
import com.chapterplayer.data.stats.ListeningLogEntry
import com.chapterplayer.domain.browse.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsRulesTest {

    // Monday 26 October 2026.
    private val today = epochDayOf(2026, 10, 26)
    private val hour = 3_600L

    private fun entry(day: Int, book: String = "abs_a", wall: Long = 600, bookSec: Long = wall, source: ServerSource = ServerSource.ABS) =
        ListeningLogEntry(day, book, if (source == ServerSource.ABS) "s1" else "p1", source, wall, bookSec)

    private fun book(
        id: String, author: String = "Author", narrator: String = "", series: String? = null, seq: String? = null,
        durationMs: Long = 36_000_000L, source: ServerSource = ServerSource.ABS, serverId: String = "s1",
    ) = AudioBook(id, "Title $id", author, narrator, durationMs, emptyList(), "", null, source, serverId, seriesName = series, seriesSequence = seq)

    private fun item(book: AudioBook, positionMs: Long? = null, finished: Boolean = false, playedAt: Long = 0L, download: DownloadState = DownloadState.NOT_DOWNLOADED) =
        BrowseItem(book, positionMs?.let { ReadingProgress(book.id, it, book.durationMs, playedAt, finished) }, download)

    // ---- Periods -----------------------------------------------------------------------------------------------------

    @Test
    fun `the week runs from Monday - the month from the first - the year from January`() {
        val sunday = epochDayOf(2026, 11, 1)
        assertEquals(DayRange(today, today), periodRange(StatsPeriod.WEEK, today, null))
        assertEquals(DayRange(today, sunday), periodRange(StatsPeriod.WEEK, sunday, null))
        assertEquals(DayRange(epochDayOf(2026, 10, 1), today), periodRange(StatsPeriod.MONTH, today, null))
        assertEquals(DayRange(epochDayOf(2026, 1, 1), today), periodRange(StatsPeriod.YEAR, today, null))
        assertEquals(DayRange(today - 40, today), periodRange(StatsPeriod.ALL_TIME, today, today - 40))
        assertEquals(DayRange(today, today), periodRange(StatsPeriod.ALL_TIME, today, null))
    }

    @Test
    fun `the previous period is the whole period before`() {
        assertEquals(DayRange(today - 7, today - 1), previousRange(StatsPeriod.WEEK, today))
        assertEquals(DayRange(epochDayOf(2026, 9, 1), epochDayOf(2026, 9, 30)), previousRange(StatsPeriod.MONTH, today))
        assertEquals(DayRange(epochDayOf(2025, 12, 1), epochDayOf(2025, 12, 31)), previousRange(StatsPeriod.MONTH, epochDayOf(2026, 1, 15)))
        assertEquals(DayRange(epochDayOf(2025, 1, 1), epochDayOf(2025, 12, 31)), previousRange(StatsPeriod.YEAR, today))
        assertNull(previousRange(StatsPeriod.ALL_TIME, today))
    }

    @Test
    fun `the change compares the average per day`() {
        // 10 h in 10 days against 15 h in 30 days: 1 h a day against 0.5 h a day.
        assertEquals(100, changePercent(10 * hour, 10, 15 * hour, 30))
        assertEquals(-50, changePercent(5 * hour, 10, 10 * hour, 10))
        assertEquals(0, changePercent(hour, 1, hour, 1))
        assertNull(changePercent(hour, 1, 0, 30))
        assertNull(changePercent(hour, 0, hour, 30))
    }

    // ---- Day totals ---------------------------------------------------------------------------------------------------

    @Test
    fun `a day takes the larger of the app's log and the servers' history`() {
        val totals = dayTotals(
            listOf(
                entry(today, wall = 100), entry(today, book = "plex_b", wall = 50, source = ServerSource.PLEX),
                entry(today - 2, wall = 900),
            ),
            listOf(
                // Today the history holds more: listening in another client too.
                ImportedDay("s1", today, 9_999),
                // Only imported, summed over servers.
                ImportedDay("s1", today - 1, 300), ImportedDay("s2", today - 1, 200),
                // The app's own listening that reached the server: not counted twice.
                ImportedDay("s1", today - 2, 600),
            )
        )
        assertEquals(DayTotal(9_999, imported = true), totals[today])
        assertEquals(DayTotal(500, imported = true), totals[today - 1])
        assertEquals(DayTotal(900, imported = false), totals[today - 2])
        assertEquals(3, totals.size)
    }

    @Test
    fun `on an overlap day the source split adds only the history beyond the log - and matches the total`() {
        val log = listOf(entry(today, wall = 300), entry(today, "plex_b", 100, source = ServerSource.PLEX))
        val totals = dayTotals(log, listOf(ImportedDay("s1", today, 1_000)))
        val split = sourceSplit(log, totals, DayRange(today, today))
        assertEquals(mapOf(ServerSource.ABS to 900L, ServerSource.PLEX to 100L), split)
        assertEquals(totals.getValue(today).seconds, split.values.sum())
    }

    @Test
    fun `no data gives no totals`() {
        assertTrue(dayTotals(emptyList(), emptyList()).isEmpty())
    }

    // ---- Streaks ------------------------------------------------------------------------------------------------------

    @Test
    fun `the streak counts back from today - or from yesterday until today has a minute`() {
        val days = (0..4).associate { (today - it) to DayTotal(600, false) }
        assertEquals(5, currentStreak(days, today))
        val notYetToday = days - today
        assertEquals(4, currentStreak(notYetToday, today))
        assertEquals(4, currentStreak(notYetToday + (today to DayTotal(59, false)), today))
        assertEquals(5, currentStreak(notYetToday + (today to DayTotal(60, false)), today))
    }

    @Test
    fun `a day under a minute breaks the streak and no listening is no streak`() {
        val days = mapOf(today to DayTotal(600, false), today - 1 to DayTotal(30, false), today - 2 to DayTotal(600, false))
        assertEquals(1, currentStreak(days, today))
        assertEquals(0, currentStreak(emptyMap(), today))
        assertEquals(0, currentStreak(mapOf(today - 2 to DayTotal(600, false)), today))
    }

    @Test
    fun `imported days count for the streak`() {
        val days = mapOf(today to DayTotal(600, false), today - 1 to DayTotal(600, true))
        assertEquals(2, currentStreak(days, today))
    }

    // ---- Longest day, weekdays, time saved ------------------------------------------------------------------------------

    @Test
    fun `the longest day is the most listened in the range - the latest on a tie`() {
        val days = mapOf(today to DayTotal(100, false), today - 1 to DayTotal(300, false), today - 2 to DayTotal(300, false), today - 30 to DayTotal(999, false))
        assertEquals(today - 1 to 300L, longestDay(days, DayRange(today - 7, today)))
        assertNull(longestDay(emptyMap(), DayRange(today - 7, today)))
    }

    @Test
    fun `weekday totals add up each weekday in the range`() {
        val days = mapOf(today to DayTotal(100, false), today - 7 to DayTotal(50, false), today - 1 to DayTotal(30, true), today - 100 to DayTotal(999, false))
        val totals = weekdayTotals(days, DayRange(today - 14, today))
        assertEquals(listOf(150L, 0, 0, 0, 0, 0, 30L), totals)
    }

    @Test
    fun `time saved is book time beyond wall time - never below zero`() {
        val log = listOf(entry(today, wall = 600, bookSec = 900), entry(today - 1, book = "abs_b", wall = 600, bookSec = 500), entry(today - 40, wall = 100, bookSec = 1_000))
        assertEquals(200L, timeSaved(log, DayRange(today - 7, today)))
        assertEquals(0L, timeSaved(listOf(entry(today, wall = 600, bookSec = 300)), DayRange(today, today)))
    }

    // ---- Top lists ------------------------------------------------------------------------------------------------------

    @Test
    fun `top authors add the time of their books and a co-author gets the whole book`() {
        val items = listOf(
            item(book("abs_a", author = "Ann Lee, Bob Ray")),
            item(book("abs_b", author = "Bob Ray")),
            item(book("abs_c", author = "Cy Doe")),
        )
        val log = listOf(entry(today, "abs_a", 600), entry(today, "abs_b", 300), entry(today, "abs_c", 600), entry(today - 60, "abs_c", 9_000))
        val top = topListened(TopKind.AUTHORS, log, emptyList(), items, DayRange(today - 7, today), includeEarlier = false)
        assertEquals(listOf(Ranked("bob ray", "Bob Ray", 900), Ranked("ann lee", "Ann Lee", 600), Ranked("cy doe", "Cy Doe", 600)), top)
    }

    @Test
    fun `a merged book counts once under its entry`() {
        val abs = item(book("abs_a", author = "Ann Lee", series = "Saga"))
        val plex = item(book("plex_a", author = "Ann Lee", source = ServerSource.PLEX, serverId = "p1"))
        val merged = abs.copy(sources = listOf(abs, plex))
        val log = listOf(entry(today, "abs_a", 600), entry(today, "plex_a", 300, source = ServerSource.PLEX))
        val authors = topListened(TopKind.AUTHORS, log, emptyList(), listOf(merged), DayRange(today, today), includeEarlier = false)
        assertEquals(listOf(Ranked("ann lee", "Ann Lee", 900)), authors)
        val series = topListened(TopKind.SERIES, log, emptyList(), listOf(merged), DayRange(today, today), includeEarlier = false)
        assertEquals(listOf(Ranked("saga", "Saga", 900)), series)
    }

    @Test
    fun `narrators and series skip books without them and unknown books are left out`() {
        val items = listOf(item(book("abs_a", narrator = "Nia Fox", series = "Saga")), item(book("abs_b")))
        val log = listOf(entry(today, "abs_a", 600), entry(today, "abs_b", 300), entry(today, "abs_gone", 999))
        assertEquals(listOf(Ranked("nia fox", "Nia Fox", 600)), topListened(TopKind.NARRATORS, log, emptyList(), items, DayRange(today, today), false))
        assertEquals(listOf(Ranked("saga", "Saga", 600)), topListened(TopKind.SERIES, log, emptyList(), items, DayRange(today, today), false))
    }

    @Test
    fun `all time adds the server's totals beyond what the app had logged at the import`() {
        val items = listOf(item(book("abs_a", author = "Ann Lee")), item(book("abs_b", author = "Bob Ray")))
        val log = listOf(entry(today, "abs_a", 600))
        val imported = listOf(
            ImportedItem("s1", "abs_a", 1_000, loggedAtImport = 600), ImportedItem("s1", "abs_b", 300), ImportedItem("s1", "abs_gone", 5_000)
        )
        val top = topListened(TopKind.AUTHORS, log, imported, items, DayRange(today - 400, today), includeEarlier = true)
        // abs_a: 600 logged + (1000 - 600) the app never logged. abs_b: 300 from the server only.
        assertEquals(listOf(Ranked("ann lee", "Ann Lee", 1_000), Ranked("bob ray", "Bob Ray", 300)), top)
    }

    @Test
    fun `all time lists keep growing with listening logged after the import`() {
        val h = 3_600L
        val items = listOf(item(book("abs_a", author = "Ann Lee")))
        fun allTime(log: List<ListeningLogEntry>, imported: List<ImportedItem>) =
            topListened(TopKind.AUTHORS, log, imported, items, DayRange(today - 400, today), includeEarlier = true).single().seconds
        // 2 h logged in the app, then an import whose 10 h include those 2 h: the book shows 10 h, not 12.
        val before = listOf(entry(today - 3, "abs_a", 2 * h))
        val imported = listOf(ImportedItem("s1", "abs_a", 10 * h, loggedAtImport = 2 * h))
        assertEquals(10 * h, allTime(before, imported))
        // 9 h more logged after the import: it grows, without waiting for another import.
        assertEquals(19 * h, allTime(before + entry(today, "abs_a", 9 * h), imported))
        // An import made before any logging: the 2 h logged afterwards are new listening on top.
        assertEquals(12 * h, allTime(listOf(entry(today, "abs_a", 2 * h)), listOf(ImportedItem("s1", "abs_a", 10 * h, loggedAtImport = 0))))
    }

    @Test
    fun `top lists keep the first five and break ties by name`() {
        val items = (1..7).map { item(book("abs_$it", author = "Author ${'H' - it}")) }
        val log = items.map { entry(today, it.book.id, 100) }
        val top = topListened(TopKind.AUTHORS, log, emptyList(), items, DayRange(today, today), false)
        assertEquals(listOf("Author A", "Author B", "Author C", "Author D", "Author E"), top.map { it.name })
    }

    // ---- Sources, finished books, library ------------------------------------------------------------------------------

    @Test
    fun `the source split follows each day's own source and imported days are Audiobookshelf`() {
        val log = listOf(entry(today, wall = 300), entry(today, "plex_b", 100, source = ServerSource.PLEX), entry(today - 50, "plex_b", 999, source = ServerSource.PLEX))
        val totals = dayTotals(log, listOf(ImportedDay("s1", today - 1, 600), ImportedDay("s1", today, 50)))
        assertEquals(mapOf(ServerSource.ABS to 900L, ServerSource.PLEX to 100L), sourceSplit(log, totals, DayRange(today - 7, today)))
    }

    @Test
    fun `books finished count each entry once by the day of the finishing play`() {
        val dayMs = { d: Int -> d * MS_PER_DAY + 3_600_000L }
        val a = item(book("abs_a"), positionMs = 100, finished = true, playedAt = dayMs(today))
        val b = item(book("abs_b", durationMs = 100_000), positionMs = 98_000, playedAt = dayMs(today - 1))
        val c = item(book("abs_c"), positionMs = 100, finished = true, playedAt = dayMs(today - 30))
        val d = item(book("abs_d"), positionMs = 100, playedAt = dayMs(today))
        val items = listOf(a, b, c, d)
        assertEquals(2, booksFinished(items, DayRange(today - 7, today), DayZone.UTC))
        assertEquals(3, booksFinished(items, null, DayZone.UTC))
    }

    @Test
    fun `a finished book without a play time counts in all time only`() {
        val undated = item(book("abs_a"), positionMs = 100, finished = true, playedAt = 0L)
        assertEquals(1, booksFinished(listOf(undated), null, DayZone.UTC))
        assertEquals(0, booksFinished(listOf(undated), DayRange(today - 7, today), DayZone.UTC))
    }

    @Test
    fun `library numbers count entries - length - downloads and books not started`() {
        val abs = item(book("abs_a", durationMs = 1_000_000), download = DownloadState.DOWNLOADED)
        val plex = item(book("plex_a", durationMs = 1_000_000, source = ServerSource.PLEX, serverId = "p1"))
        val merged = plex.copy(sources = listOf(plex, abs))
        val items = listOf(
            merged,
            item(book("abs_b", durationMs = 2_000_000), positionMs = 10),
            item(book("abs_c", durationMs = 3_000_000), positionMs = 0),
            item(book("abs_d", durationMs = 4_000_000), positionMs = 0, finished = true),
        )
        assertEquals(LibraryNumbers(books = 4, totalMs = 10_000_000, downloadedBooks = 1, downloadedBytes = 2_048, notStarted = 2), libraryNumbers(items, 2_048))
    }

    // ---- Series ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a series with every book finished is finished - newest first`() {
        val items = listOf(
            item(book("abs_1", series = "Alpha", seq = "1"), 10, finished = true, playedAt = 100),
            item(book("abs_2", series = "Alpha", seq = "2"), 10, finished = true, playedAt = 500),
            item(book("abs_3", series = "Beta", seq = "1"), 10, finished = true, playedAt = 900),
            item(book("abs_4", series = "beta ", seq = "2"), 10, finished = true, playedAt = 200),
        )
        val progress = seriesProgress(items)
        assertEquals(listOf(FinishedSeries("beta", "Beta", 2, 900), FinishedSeries("alpha", "Alpha", 2, 500)), progress.finished)
        assertTrue(progress.toFinish.isEmpty())
    }

    @Test
    fun `a series needs two books and an untouched series is in neither list`() {
        val items = listOf(
            item(book("abs_1", series = "Solo"), 10, finished = true, playedAt = 100),
            item(book("abs_2", series = "Fresh")),
            item(book("abs_3", series = "Fresh")),
        )
        assertEquals(SeriesProgress(emptyList(), emptyList()), seriesProgress(items))
    }

    @Test
    fun `series to finish show finished out of total and time left - fewest remaining first`() {
        val items = listOf(
            // Long: 1 of 3 finished, one in progress (2 left).
            item(book("abs_l1", series = "Long", durationMs = 10 * 3_600_000L), 10, finished = true, playedAt = 1),
            item(book("abs_l2", series = "Long", durationMs = 10 * 3_600_000L), positionMs = 4 * 3_600_000L),
            item(book("abs_l3", series = "Long", durationMs = 10 * 3_600_000L)),
            // Near: 2 of 3 finished (1 left).
            item(book("abs_n1", series = "Near"), 10, finished = true, playedAt = 1),
            item(book("abs_n2", series = "Near"), 10, finished = true, playedAt = 2),
            item(book("abs_n3", series = "Near", durationMs = 5 * 3_600_000L)),
            // Started only: nothing finished, one in progress (2 left, less time than Long).
            item(book("abs_s1", series = "Started", durationMs = 3_600_000L), positionMs = 1_800_000L),
            item(book("abs_s2", series = "Started", durationMs = 3_600_000L)),
        )
        val toFinish = seriesProgress(items).toFinish
        assertEquals(
            listOf(
                SeriesToFinish("near", "Near", 2, 3, 5 * 3_600_000L),
                SeriesToFinish("started", "Started", 0, 2, 5_400_000L),
                SeriesToFinish("long", "Long", 1, 3, 16 * 3_600_000L),
            ),
            toFinish
        )
    }

    @Test
    fun `a book within seconds of its end counts as finished for a series`() {
        val items = listOf(
            item(book("abs_1", series = "Alpha", durationMs = 100_000), positionMs = 97_000, playedAt = 300),
            item(book("abs_2", series = "Alpha"), 10, finished = true, playedAt = 200),
        )
        assertEquals(listOf(FinishedSeries("alpha", "Alpha", 2, 300)), seriesProgress(items).finished)
    }

    @Test
    fun `a merged book takes its series from any copy and counts once`() {
        val plex = item(book("plex_1", source = ServerSource.PLEX, serverId = "p1"), 10, finished = true, playedAt = 50)
        val abs = item(book("abs_1", series = "Alpha", seq = "1"))
        // The merged entry carries the Audiobookshelf series (as mergeDuplicates builds it) and the newest progress.
        val merged = plex.copy(book = plex.book.copy(seriesName = "Alpha", seriesSequence = "1"), sources = listOf(plex, abs))
        val items = listOf(merged, item(book("abs_2", series = "Alpha", seq = "2"), 10, finished = true, playedAt = 60))
        assertEquals(listOf(FinishedSeries("alpha", "Alpha", 2, 60)), seriesProgress(items).finished)
    }

    // ---- Heat grid ------------------------------------------------------------------------------------------------------

    @Test
    fun `the week grid is this week with days to come marked`() {
        val grid = heatGrid(mapOf(today to DayTotal(600, false)), today, StatsPeriod.WEEK)
        assertEquals(1, grid.size)
        assertEquals((today..today + 6).toList(), grid.single().map { it.day })
        assertEquals(listOf(4, 0, 0, 0, 0, 0, 0), grid.single().map { it.level })
        assertEquals(listOf(false, true, true, true, true, true, true), grid.single().map { it.future })
    }

    @Test
    fun `other periods show fourteen weeks scaled to the busiest day`() {
        val days = mapOf(today to DayTotal(4_000, false), today - 1 to DayTotal(1_000, false), today - 2 to DayTotal(30, false), today - 90 to DayTotal(2_000, true))
        val grid = heatGrid(days, today, StatsPeriod.MONTH)
        assertEquals(14, grid.size)
        assertTrue(grid.all { it.size == 7 })
        // The last column is this week, from Monday (today) on.
        assertEquals(today, grid.last().first().day)
        assertEquals(today - 13 * 7, grid.first().first().day)
        val byDay = grid.flatten().associateBy { it.day }
        assertEquals(4, byDay.getValue(today).level)
        assertEquals(1, byDay.getValue(today - 1).level)
        assertEquals(0, byDay.getValue(today - 2).level)
        assertEquals(2, byDay[today - 90]?.level)
    }

    @Test
    fun `an empty grid has every level at zero`() {
        assertTrue(heatGrid(emptyMap(), today, StatsPeriod.YEAR).flatten().all { it.level == 0 })
    }
}
