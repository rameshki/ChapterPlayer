package com.chapterplayer.domain.stats

import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.stats.ImportOutcome
import kotlin.math.abs

/* The words and numbers the statistics page shows and reads out. Pure, so they are tested on the host. */

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTH_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The one-letter weekday labels under the bars, Monday first. */
val WEEKDAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

fun weekdayName(weekday: Int): String = WEEKDAY_LONG[weekday.mod(7)]

/** "38 h 12 m", "3 h 02 m", "12 m"; under a minute is "0 m". */
fun formatListened(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0L) / 60
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0L) "$m m" else "$h h ${m.toString().padStart(2, '0')} m"
}

/** Whole hours for long totals ("14,380 h"), minutes below an hour. */
fun formatHoursTotal(ms: Long): String {
    val hours = ms.coerceAtLeast(0L) / 3_600_000L
    return if (hours == 0L) "${ms.coerceAtLeast(0L) / 60_000L} m" else "${groupThousands(hours)} h"
}

/** "14 h left", "45 m left". */
fun formatTimeLeft(ms: Long): String {
    val minutes = ms.coerceAtLeast(0L) / 60_000L
    return if (minutes < 60) "$minutes m left" else "${(minutes + 30) / 60} h left"
}

/** For screen readers: "2 hours", "1 hour 5 minutes", "12 minutes", "less than a minute". */
fun spokenDuration(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0L) / 60
    val h = minutes / 60
    val m = minutes % 60
    fun unit(n: Long, one: String) = if (n == 1L) "1 $one" else "$n ${one}s"
    return when {
        h == 0L && m == 0L -> "less than a minute"
        h == 0L -> unit(m, "minute")
        m == 0L -> unit(h, "hour")
        else -> "${unit(h, "hour")} ${unit(m, "minute")}"
    }
}

/** "1,204". */
fun groupThousands(n: Long): String {
    val digits = abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** "Sat 11 Oct". */
fun dayLabel(epochDay: Int): String {
    val d = civilDate(epochDay)
    return "${WEEKDAY_SHORT[weekdayOf(epochDay)]} ${d.day} ${MONTH_SHORT[d.month - 1]}"
}

fun periodLabel(period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> "Week"
    StatsPeriod.MONTH -> "Month"
    StatsPeriod.YEAR -> "Year"
    StatsPeriod.ALL_TIME -> "All time"
}

/** The small heading over the big number. */
fun heroLabel(period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> "Listened this week"
    StatsPeriod.MONTH -> "Listened this month"
    StatsPeriod.YEAR -> "Listened this year"
    StatsPeriod.ALL_TIME -> "Listened in all"
}

private fun previousName(period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> "last week"
    StatsPeriod.MONTH -> "last month"
    StatsPeriod.YEAR -> "last year"
    StatsPeriod.ALL_TIME -> ""
}

/** "1 h 14 m a day on average · up 18% on last month". */
fun heroDetail(report: StatsReport): String {
    val average = "${formatListened(report.averagePerDaySeconds)} a day on average"
    val change = report.changePercent ?: return average
    val previous = previousName(report.period)
    val comparison = when {
        change > 0 -> "up $change% on $previous"
        change < 0 -> "down ${-change}% on $previous"
        else -> "the same as $previous"
    }
    return "$average · $comparison"
}

fun streakText(days: Int): String = if (days == 1) "1 day" else "$days days"

fun booksText(n: Int): String = if (n == 1) "1 book" else "${groupThousands(n.toLong())} books"

/** "3 of 7 finished · 14 h left". */
fun seriesToFinishLine(series: SeriesToFinish): String =
    "${series.finished} of ${series.total} finished · ${formatTimeLeft(series.remainingMs)}"

fun sourceName(source: ServerSource): String = when (source) {
    ServerSource.ABS -> "Audiobookshelf"
    ServerSource.PLEX -> "Plex"
}

/** Whole percentages that add up to 100 (largest remainder), largest share first. */
fun sourceShares(sources: Map<ServerSource, Long>): List<Pair<ServerSource, Int>> {
    val total = sources.values.filter { it > 0 }.sum()
    if (total <= 0L) return emptyList()
    val exact = sources.filterValues { it > 0 }.map { (source, s) -> source to s * 100.0 / total }
    val floors = exact.map { it.first to it.second.toInt() }.toMutableList()
    var left = 100 - floors.sumOf { it.second }
    val byRemainder = exact.indices.sortedByDescending { exact[it].second - exact[it].second.toInt() }
    for (i in byRemainder) {
        if (left <= 0) break
        floors[i] = floors[i].first to floors[i].second + 1
        left--
    }
    return floors.sortedWith(compareByDescending<Pair<ServerSource, Int>> { it.second }.thenBy { it.first.ordinal })
}

/** "Listening by weekday: Saturday is highest, 2 hours". */
fun weekdayDescription(weekdays: List<Long>): String {
    val best = weekdays.indices.maxByOrNull { weekdays[it] }
    if (best == null || weekdays[best] < 60L) return "Listening by weekday: nothing yet"
    return "Listening by weekday: ${weekdayName(best)} is highest, ${spokenDuration(weekdays[best])}"
}

/** "Every day: listening on 23 of the last 98 days, most on Sat 11 Oct, 3 hours 2 minutes". */
fun heatDescription(grid: List<List<HeatCell>>): String {
    val shown = grid.flatten().filter { !it.future }
    val listened = shown.filter { it.level > 0 }
    val best = listened.maxWithOrNull(compareBy<HeatCell>({ it.seconds }, { it.day }))
        ?: return "Every day: no listening in the last ${shown.size} days"
    return "Every day: listening on ${listened.size} of the last ${shown.size} days, most on ${dayLabel(best.day)}, ${spokenDuration(best.seconds)}"
}

/** "Audiobookshelf 68%, Plex 32%". */
fun sourcesDescription(shares: List<Pair<ServerSource, Int>>): String =
    if (shares.isEmpty()) "Where you listened: nothing yet"
    else "Where you listened: " + shares.joinToString(", ") { "${sourceName(it.first)} ${it.second}%" }

/** The line under Statistics in the Settings hub. */
const val STATS_SUMMARY = "Listening time, streaks and series"

const val EMPTY_STATS_TEXT = "Start listening and your stats will appear here"

const val IMPORTED_ONLY_NOTE =
    "These figures come from your Audiobookshelf history. Books, authors and time saved count from when this app records your listening."

/** What the Refresh button reports. */
fun importOutcomeText(outcome: ImportOutcome): String {
    fun servers(n: Int) = if (n == 1) "1 server" else "$n servers"
    val parts = mutableListOf<String>()
    if (outcome.imported > 0) parts += "History updated from ${servers(outcome.imported)}"
    if (outcome.failed > 0) parts += "could not reach ${servers(outcome.failed)}"
    if (outcome.signInRequired > 0) parts += "sign in again to update ${servers(outcome.signInRequired)}"
    if (parts.isEmpty()) return "No Audiobookshelf server to read listening history from"
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** The fraction of the tallest bar a weekday's bar fills (0 to 1). */
fun barFraction(value: Long, max: Long): Float = if (max <= 0L) 0f else (value.toDouble() / max).toFloat().coerceIn(0f, 1f)