package com.chapterplayer.domain.diagnostics

/** Small text helpers for the report; no java.time or String.format, so they run on every target. */

/** "2026-11-02 14:03:05 UTC" for a time in epoch milliseconds; "never" for 0 or less. */
fun formatUtc(epochMs: Long): String {
    if (epochMs <= 0L) return "never"
    val totalSeconds = epochMs / 1000L
    val days = totalSeconds / 86_400L
    val secondOfDay = (totalSeconds % 86_400L).toInt()
    // Civil date from days since 1970-01-01 (proleptic Gregorian).
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val mp = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * mp + 2L) / 5L + 1L).toInt()
    val month = (if (mp < 10) mp + 3L else mp - 9L).toInt()
    val year = (yearOfEra + era * 400L + if (month <= 2) 1L else 0L).toInt()
    return "${year}-${two(month)}-${two(day)} ${two(secondOfDay / 3600)}:${two(secondOfDay % 3600 / 60)}:${two(secondOfDay % 60)} UTC"
}

/** "1:02:03" for a length in milliseconds (hours shown only from one hour; "0:05" under it). */
fun formatClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    val h = seconds / 3600L
    val m = seconds % 3600L / 60L
    val s = seconds % 60L
    return if (h > 0) "$h:${two(m.toInt())}:${two(s.toInt())}" else "$m:${two(s.toInt())}"
}

/** "12.3 MB" style size with one decimal. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val tenths = (value * 10.0 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}

private fun two(n: Int): String = if (n < 10) "0$n" else "$n"

/** The last [n] characters of a Plex rating key, so a track can be told apart without the whole key. */
fun keySuffix(key: String, n: Int = 3): String = "..." + key.takeLast(n)
