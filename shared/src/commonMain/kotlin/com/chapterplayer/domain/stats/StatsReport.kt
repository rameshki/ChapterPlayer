package com.chapterplayer.domain.stats

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportedDay
import com.chapterplayer.data.stats.ImportedItem
import com.chapterplayer.data.stats.ListeningLogEntry
import com.chapterplayer.domain.browse.BrowseItem

/** Everything the statistics are made from. [items] are the merged browse entries; [today] is an epoch day in [zone]. */
data class StatsInput(
    val log: List<ListeningLogEntry> = emptyList(),
    val importedDays: List<ImportedDay> = emptyList(),
    val importedItems: List<ImportedItem> = emptyList(),
    val items: List<BrowseItem> = emptyList(),
    val downloadedBytes: Long = 0L,
    val today: Int = 0,
    val zone: DayZone = DayZone.UTC,
)

/** The statistics page for one period. Times are seconds unless named otherwise. */
data class StatsReport(
    val period: StatsPeriod,
    val range: DayRange,
    val listenedSeconds: Long,
    val averagePerDaySeconds: Long,
    /** Change of the average per day against the previous period, in percent; null when there is none to compare. */
    val changePercent: Int?,
    val booksFinished: Int,
    val streakDays: Int,
    val timeSavedSeconds: Long,
    /** The busiest day of the period and its seconds. */
    val longestDay: Pair<Int, Long>?,
    val heat: List<List<HeatCell>>,
    /** Monday first. */
    val weekdays: List<Long>,
    val top: Map<TopKind, List<Ranked>>,
    val sources: Map<ServerSource, Long>,
    val library: LibraryNumbers,
    val series: SeriesProgress,
    /** Nothing has been listened to at all yet (no log, no imported history). */
    val nothingYet: Boolean,
    /** The period has listening, but all of it comes from a server's history (no per-book detail). */
    val importedOnly: Boolean,
)

/** The report for [period]; see the rules in StatsRules.kt. */
fun buildStats(input: StatsInput, period: StatsPeriod): StatsReport {
    val totals = dayTotals(input.log, input.importedDays)
    val listenedDays = totals.filterValues { it.seconds > 0L }
    val today = input.today
    val range = periodRange(period, today, listenedDays.keys.minOrNull())
    val listened = listenedDays.filterKeys { it in range }.values.sumOf { it.seconds }
    val previous = previousRange(period, today)
    val previousSeconds = previous?.let { p -> listenedDays.filterKeys { it in p }.values.sumOf { it.seconds } } ?: 0L
    val inRange = listenedDays.filterKeys { it in range }.values
    val allTime = period == StatsPeriod.ALL_TIME
    return StatsReport(
        period = period,
        range = range,
        listenedSeconds = listened,
        averagePerDaySeconds = if (range.days > 0) listened / range.days else 0L,
        changePercent = previous?.let { changePercent(listened, range.days, previousSeconds, it.days) },
        booksFinished = booksFinished(input.items, if (allTime) null else range, input.zone),
        streakDays = currentStreak(totals, today),
        timeSavedSeconds = timeSaved(input.log, range),
        longestDay = longestDay(totals, range),
        heat = heatGrid(totals, today, period),
        weekdays = weekdayTotals(totals, range),
        top = TopKind.entries.associateWith { topListened(it, input.log, input.importedItems, input.items, range, includeEarlier = allTime) },
        sources = sourceSplit(input.log, totals, range),
        library = libraryNumbers(input.items, input.downloadedBytes),
        series = seriesProgress(input.items),
        nothingYet = listenedDays.isEmpty(),
        importedOnly = inRange.isNotEmpty() && inRange.all { it.imported },
    )
}
