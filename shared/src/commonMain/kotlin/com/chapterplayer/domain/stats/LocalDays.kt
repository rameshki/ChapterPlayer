package com.chapterplayer.domain.stats

/*
 * Days as the listener lives them: an "epoch day" is the number of local calendar days since 1970-01-01 in the
 * device's time zone. Pure Kotlin (no java.time), so the same rules run on iOS. The zone is injected, so tests can
 * put midnight and daylight saving changes wherever they need them.
 */

/** The device's time zone as far as day boundaries need it: the offset from UTC in force at an instant. */
fun interface DayZone {
    fun offsetMsAt(epochMs: Long): Long

    companion object {
        val UTC = DayZone { 0L }
    }
}

const val MS_PER_DAY = 86_400_000L

/** The local day [epochMs] falls on in [zone]. */
fun epochDayOf(epochMs: Long, zone: DayZone): Int =
    (epochMs + zone.offsetMsAt(epochMs)).floorDiv(MS_PER_DAY).toInt()

/** A calendar date; [month] is 1 to 12. */
data class CivilDate(val year: Int, val month: Int, val day: Int)

/** The epoch day of a calendar date (proleptic Gregorian). */
fun epochDayOf(year: Int, month: Int, day: Int): Int {
    // Howard Hinnant's days_from_civil.
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** The calendar date of an epoch day. */
fun civilDate(epochDay: Int): CivilDate {
    // Howard Hinnant's civil_from_days.
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return CivilDate(year, month, day)
}

/** 0 for Monday up to 6 for Sunday. */
fun weekdayOf(epochDay: Int): Int = (epochDay + 3).mod(7)

fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private val ISO_DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

/** The epoch day of a "YYYY-MM-DD" text (as Audiobookshelf keys its daily totals); null for anything else. */
fun parseIsoDay(text: String): Int? {
    val match = ISO_DAY.matchEntire(text.trim()) ?: return null
    val (y, m, d) = match.destructured
    val year = y.toInt()
    val month = m.toInt()
    val day = d.toInt()
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return epochDayOf(year, month, day)
}
