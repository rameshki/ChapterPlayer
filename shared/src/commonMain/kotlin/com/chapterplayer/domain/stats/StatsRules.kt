package com.chapterplayer.domain.stats

import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportedDay
import com.chapterplayer.data.stats.ImportedItem
import com.chapterplayer.data.stats.ListeningLogEntry
import com.chapterplayer.domain.browse.BrowseItem
import com.chapterplayer.domain.browse.groupBySeries
import com.chapterplayer.domain.browse.splitPeople
import com.chapterplayer.domain.playback.isFinishedAt
import kotlin.math.roundToInt

/*
 * The listening statistics as pure rules. Days are epoch days in the device's zone (see LocalDays.kt). Listening
 * time is the app's own log; a day the app logged nothing for takes the servers' imported total instead (so the
 * charts do not start empty). Books are the merged browse entries, so a book on several servers counts once.
 */

enum class StatsPeriod { WEEK, MONTH, YEAR, ALL_TIME }

/** The days [first] to [last], both included. */
data class DayRange(val first: Int, val last: Int) {
    val days: Int get() = if (last >= first) last - first + 1 else 0
    operator fun contains(day: Int): Boolean = day in first..last
}

/**
 * The days of [period] up to [today]: this week from Monday, this month from the 1st, this year from 1 January,
 * and all time from [firstDay] (the first day with listening; today when there is none).
 */
fun periodRange(period: StatsPeriod, today: Int, firstDay: Int?): DayRange {
    val date = civilDate(today)
    return when (period) {
        StatsPeriod.WEEK -> DayRange(today - weekdayOf(today), today)
        StatsPeriod.MONTH -> DayRange(epochDayOf(date.year, date.month, 1), today)
        StatsPeriod.YEAR -> DayRange(epochDayOf(date.year, 1, 1), today)
        StatsPeriod.ALL_TIME -> DayRange(minOf(firstDay ?: today, today), today)
    }
}

/** The whole period before the current one; null for all time. */
fun previousRange(period: StatsPeriod, today: Int): DayRange? {
    val current = periodRange(period, today, null)
    return when (period) {
        StatsPeriod.WEEK -> DayRange(current.first - 7, current.first - 1)
        StatsPeriod.MONTH -> {
            val last = current.first - 1
            val d = civilDate(last)
            DayRange(epochDayOf(d.year, d.month, 1), last)
        }
        StatsPeriod.YEAR -> {
            val year = civilDate(today).year - 1
            DayRange(epochDayOf(year, 1, 1), epochDayOf(year, 12, 31))
        }
        StatsPeriod.ALL_TIME -> null
    }
}

/**
 * The change in the average per day, in whole percent: [current] seconds over [currentDays] days against [previous]
 * over [previousDays]. Averages make a period that has only just begun comparable with a whole one. Null when there
 * is nothing to compare with.
 */
fun changePercent(current: Long, currentDays: Int, previous: Long, previousDays: Int): Int? {
    if (currentDays <= 0 || previousDays <= 0 || previous <= 0L) return null
    val now = current.toDouble() / currentDays
    val before = previous.toDouble() / previousDays
    return ((now / before - 1.0) * 100.0).roundToInt()
}

/** One day's listening in seconds; [imported] when the servers' history for the day is more than the app logged. */
data class DayTotal(val seconds: Long, val imported: Boolean)

/**
 * Seconds per day: the larger of what the app logged and what the servers' histories hold for the day (summed over
 * servers). The app's own listening reaches Audiobookshelf too, so adding the two would count it twice; taking the
 * larger keeps listening in another client on a day the app also logged (and the day the feature arrived), at the
 * cost of under-counting a day split between this app on Plex and another Audiobookshelf client.
 */
fun dayTotals(log: List<ListeningLogEntry>, imported: List<ImportedDay>): Map<Int, DayTotal> {
    val local = loggedPerDay(log)
    val fromServers = HashMap<Int, Long>()
    for (d in imported) fromServers[d.day] = (fromServers[d.day] ?: 0L) + d.seconds
    val result = HashMap<Int, DayTotal>()
    for (day in local.keys + fromServers.keys) {
        val logged = local[day] ?: 0L
        val server = fromServers[day] ?: 0L
        result[day] = if (server > logged) DayTotal(server, imported = true) else DayTotal(logged, imported = false)
    }
    return result
}

private fun loggedPerDay(log: List<ListeningLogEntry>): Map<Int, Long> {
    val local = HashMap<Int, Long>()
    for (e in log) local[e.day] = (local[e.day] ?: 0L) + e.wallSeconds
    return local
}

/** A day counts towards a streak with at least this much listening. */
const val STREAK_MIN_SECONDS = 60L

private fun counts(totals: Map<Int, DayTotal>, day: Int) = (totals[day]?.seconds ?: 0L) >= STREAK_MIN_SECONDS

/**
 * Consecutive days with at least a minute of listening, ending today; while today has less than that, ending
 * yesterday (the streak is not broken until the day is over).
 */
fun currentStreak(totals: Map<Int, DayTotal>, today: Int): Int {
    var day = if (counts(totals, today)) today else today - 1
    var streak = 0
    while (counts(totals, day)) {
        streak++
        day--
    }
    return streak
}

/** The day in [range] with the most listening and its seconds (the latest of equal days); null with none. */
fun longestDay(totals: Map<Int, DayTotal>, range: DayRange): Pair<Int, Long>? =
    totals.filter { it.key in range && it.value.seconds > 0L }
        .maxWithOrNull(compareBy<Map.Entry<Int, DayTotal>>({ it.value.seconds }, { it.key }))
        ?.let { it.key to it.value.seconds }

/** Seconds per weekday in [range], Monday first. */
fun weekdayTotals(totals: Map<Int, DayTotal>, range: DayRange): List<Long> {
    val result = LongArray(7)
    for ((day, total) in totals) if (day in range) result[weekdayOf(day)] += total.seconds
    return result.toList()
}

/** Book time heard beyond the wall time it took (listening faster than 1x), in [range]; never below zero. */
fun timeSaved(log: List<ListeningLogEntry>, range: DayRange): Long =
    log.filter { it.day in range }.sumOf { it.bookSeconds - it.wallSeconds }.coerceAtLeast(0L)

enum class TopKind { AUTHORS, NARRATORS, SERIES }

/** A name in a top list: [key] is the Library's group key for it. */
data class Ranked(val key: String, val name: String, val seconds: Long)

/** Each copy's book id to its browse entry. */
private fun entriesById(items: List<BrowseItem>): Map<String, BrowseItem> {
    val byId = HashMap<String, BrowseItem>()
    for (item in items) for (member in item.allSources) if (member.book.id !in byId) byId[member.book.id] = item
    return byId
}

/**
 * The most listened authors, narrators or series in [range]: each book's logged time goes to every name it lists
 * (a co-author gets the whole book). With [includeEarlier] (all time), a book's server total beyond what the app had
 * logged for it at the import ([ImportedItem.loggedAtImport]) is added too, as listening the app did not log. Books no longer in the library are left out.
 * Highest first, then by name; at most [limit].
 */
fun topListened(
    kind: TopKind,
    log: List<ListeningLogEntry>,
    importedItems: List<ImportedItem>,
    items: List<BrowseItem>,
    range: DayRange,
    includeEarlier: Boolean,
    limit: Int = 5,
): List<Ranked> {
    val byId = entriesById(items)
    // Keyed by the entry's primary id: one total per entry, however many copies were played.
    val perEntry = LinkedHashMap<String, Pair<BrowseItem, Long>>()
    fun add(bookId: String, seconds: Long) {
        if (seconds <= 0L) return
        val entry = byId[bookId] ?: return
        perEntry[entry.book.id] = entry to ((perEntry[entry.book.id]?.second ?: 0L) + seconds)
    }
    for (e in log) if (e.day in range) add(e.bookId, e.wallSeconds)
    // The server's total already holds what the app had logged for the book at the import; only the rest is listening
    // the app never logged. Listening logged after the import is in the log above, so the list keeps growing.
    if (includeEarlier) for (i in importedItems) add(i.bookId, i.seconds - i.loggedAtImport)
    val totals = LinkedHashMap<String, Ranked>()
    for ((entry, seconds) in perEntry.values) {
        val names = when (kind) {
            TopKind.AUTHORS -> splitPeople(entry.book.author)
            TopKind.NARRATORS -> splitPeople(entry.book.narrator)
            TopKind.SERIES -> listOfNotNull(entry.book.seriesName?.trim()?.takeIf { it.isNotEmpty() })
        }
        for (name in names.distinctBy { it.lowercase() }) {
            val key = name.lowercase()
            val old = totals[key]
            totals[key] = Ranked(key, old?.name ?: name, (old?.seconds ?: 0L) + seconds)
        }
    }
    return totals.values
        .sortedWith(compareByDescending<Ranked> { it.seconds }.thenBy { it.name.lowercase() }.thenBy { it.key })
        .take(limit)
}

/**
 * Listening in [range] by kind of server: logged time by the source of each row, plus what a day's imported history
 * holds beyond the log as Audiobookshelf. The shares add up to the hero total.
 */
fun sourceSplit(log: List<ListeningLogEntry>, totals: Map<Int, DayTotal>, range: DayRange): Map<ServerSource, Long> {
    val result = LinkedHashMap<ServerSource, Long>()
    fun add(source: ServerSource, seconds: Long) {
        if (seconds > 0L) result[source] = (result[source] ?: 0L) + seconds
    }
    for (e in log) if (e.day in range) add(e.source, e.wallSeconds)
    // What a day's history holds beyond the app's log was heard in another Audiobookshelf client.
    val logged = loggedPerDay(log)
    for ((day, total) in totals) if (day in range && total.imported) add(ServerSource.ABS, total.seconds - (logged[day] ?: 0L))
    return result
}

/** Whether an entry counts as finished: marked so, or played to within seconds of its end. */
fun isDone(item: BrowseItem): Boolean {
    val p = item.progress ?: return false
    return p.isFinished || isFinishedAt(p.positionMs, item.book.durationMs)
}

/** Entries finished on a day in [range] (by their last play), or ever with a null range. */
fun booksFinished(items: List<BrowseItem>, range: DayRange?, zone: DayZone): Int =
    items.count { item -> isDone(item) && (range == null || epochDayOf(item.progress!!.lastPlayedAt, zone) in range) }

data class LibraryNumbers(
    val books: Int,
    val totalMs: Long,
    val downloadedBooks: Int,
    val downloadedBytes: Long,
    val notStarted: Int,
)

/** The library at a glance: entries, their length, the ones with a downloaded copy, and the ones never played. */
fun libraryNumbers(items: List<BrowseItem>, downloadedBytes: Long): LibraryNumbers = LibraryNumbers(
    books = items.size,
    totalMs = items.sumOf { it.book.durationMs.coerceAtLeast(0L) },
    downloadedBooks = items.count { item -> item.allSources.any { it.downloadState == DownloadState.DOWNLOADED } },
    downloadedBytes = downloadedBytes,
    notStarted = items.count { item -> !isDone(item) && (item.progress?.positionMs ?: 0L) <= 0L },
)

/** A series whose every book is finished; [finishedAt] is the last play of its books (epoch ms). */
data class FinishedSeries(val key: String, val title: String, val books: Int, val finishedAt: Long)

/** A series begun but not done: [finished] of [total] books, [remainingMs] of listening left in the rest. */
data class SeriesToFinish(val key: String, val title: String, val finished: Int, val total: Int, val remainingMs: Long) {
    val remainingBooks: Int get() = total - finished
}

data class SeriesProgress(val finished: List<FinishedSeries>, val toFinish: List<SeriesToFinish>)

/** A series needs at least this many books to count. */
const val MIN_SERIES_BOOKS = 2

/**
 * The series (as the Library's Series view groups them, from the merged entries) that are finished, newest first,
 * and the ones to finish: at least one book finished or in progress but not all finished, fewest books left first,
 * then least time left. Series with fewer than [MIN_SERIES_BOOKS] books are left out.
 */
fun seriesProgress(items: List<BrowseItem>): SeriesProgress {
    val finished = mutableListOf<FinishedSeries>()
    val toFinish = mutableListOf<SeriesToFinish>()
    for (group in groupBySeries(items)) {
        val books = group.items
        if (books.size < MIN_SERIES_BOOKS) continue
        val done = books.filter(::isDone)
        if (done.size == books.size) {
            finished += FinishedSeries(group.key, group.title, books.size, done.maxOf { it.progress!!.lastPlayedAt })
            continue
        }
        val begun = done.isNotEmpty() || books.any { (it.progress?.positionMs ?: 0L) > 0L }
        if (!begun) continue
        val remaining = books.filterNot(::isDone).sumOf { (it.book.durationMs - (it.progress?.positionMs ?: 0L)).coerceAtLeast(0L) }
        toFinish += SeriesToFinish(group.key, group.title, done.size, books.size, remaining)
    }
    return SeriesProgress(
        finished.sortedWith(compareByDescending<FinishedSeries> { it.finishedAt }.thenBy { it.title.lowercase() }.thenBy { it.key }),
        toFinish.sortedWith(compareBy<SeriesToFinish>({ it.remainingBooks }, { it.remainingMs }, { it.title.lowercase() }, { it.key })),
    )
}

/** One square of the "Every day" grid: [level] 0 (none) to 4 (as much as the busiest day shown). */
data class HeatCell(val day: Int, val seconds: Long, val level: Int, val future: Boolean)

/** How many weeks the grid shows for a month, a year or all time. */
const val HEAT_WEEKS = 14

/**
 * The "Every day" grid as weeks (Monday to Sunday): this week alone for [StatsPeriod.WEEK], otherwise the last
 * [HEAT_WEEKS] weeks ending with this one. Levels scale to the busiest day shown; under a minute is level 0.
 */
fun heatGrid(totals: Map<Int, DayTotal>, today: Int, period: StatsPeriod): List<List<HeatCell>> {
    val thisMonday = today - weekdayOf(today)
    val weeks = if (period == StatsPeriod.WEEK) 1 else HEAT_WEEKS
    val firstMonday = thisMonday - (weeks - 1) * 7
    val seconds = { day: Int -> if (day > today) 0L else totals[day]?.seconds ?: 0L }
    var max = 0L
    for (day in firstMonday..thisMonday + 6) max = maxOf(max, seconds(day))
    return (0 until weeks).map { week ->
        (0 until 7).map { weekday ->
            val day = firstMonday + week * 7 + weekday
            val s = seconds(day)
            val level = if (s < STREAK_MIN_SECONDS || max <= 0L) 0 else ((s * 4 + max - 1) / max).toInt().coerceIn(1, 4)
            HeatCell(day, s, level, day > today)
        }
    }
}
