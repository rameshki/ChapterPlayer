package com.chapterplayer.data.remote.abs.dto

import com.chapterplayer.data.source.ABS_ID_PREFIX
import com.chapterplayer.domain.stats.ServerListeningStats
import com.chapterplayer.domain.stats.parseIsoDay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/**
 * `GET /api/me/listening-stats`: the user's listening on this server, in seconds. [days] is keyed by "YYYY-MM-DD"
 * in the SERVER's time zone (Audiobookshelf dates each session where it runs); the app takes each key as that day in
 * the phone's zone, so when the two zones differ, listening near midnight can land on the neighbouring day.
 * [items] is keyed by library item id. Recent sessions and metadata are not read.
 */
@Serializable
data class AbsListeningStatsDto(
    @SerialName("totalTime") val totalTime: Double = 0.0,
    @SerialName("days") val days: Map<String, Double> = emptyMap(),
    @SerialName("dayOfWeek") val dayOfWeek: Map<String, Double> = emptyMap(),
    @SerialName("items") val items: Map<String, AbsListeningItemDto> = emptyMap(),
) {
    /** Whole seconds per epoch day and per app book id; unreadable days and empty totals are left out. */
    fun toServerListeningStats(): ServerListeningStats {
        val dayTotals = LinkedHashMap<Int, Long>()
        for ((key, seconds) in days) {
            val day = parseIsoDay(key) ?: continue
            val whole = seconds.roundToLong()
            if (whole > 0L) dayTotals[day] = (dayTotals[day] ?: 0L) + whole
        }
        val itemTotals = LinkedHashMap<String, Long>()
        for ((key, item) in items) {
            val id = item.id.ifBlank { key }
            val whole = item.timeListening.roundToLong()
            if (id.isNotBlank() && whole > 0L) itemTotals[ABS_ID_PREFIX + id] = whole
        }
        return ServerListeningStats(dayTotals, itemTotals, totalTime.roundToLong().coerceAtLeast(0L))
    }
}

@Serializable
data class AbsListeningItemDto(
    @SerialName("id") val id: String = "",
    @SerialName("timeListening") val timeListening: Double = 0.0,
)
