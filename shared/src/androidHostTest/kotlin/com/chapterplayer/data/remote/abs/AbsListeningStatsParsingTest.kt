package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsListeningStatsDto
import com.chapterplayer.domain.stats.ServerListeningStats
import com.chapterplayer.domain.stats.epochDayOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** `GET /api/me/listening-stats`, from the synthetic fixture. */
class AbsListeningStatsParsingTest {

    private val dto by lazy { ApiJson.decodeFromString<AbsListeningStatsDto>(fixture("abs/listening-stats.json")) }

    @Test
    fun `the response gives the total, the days, the weekdays and the items`() {
        assertEquals(12960.7, dto.totalTime)
        assertEquals(5, dto.days.size)
        assertEquals(3600.0, dto.days["2026-10-20"])
        assertEquals(7560.5, dto.dayOfWeek["Saturday"])
        assertEquals(setOf("li_fixture01", "li_fixture02", "li_fixture03"), dto.items.keys)
        assertEquals(7200.5, dto.items.getValue("li_fixture01").timeListening)
    }

    @Test
    fun `the app keeps whole seconds per local day and per book with the app's ids`() {
        val stats = dto.toServerListeningStats()
        assertEquals(
            ServerListeningStats(
                days = mapOf(epochDayOf(2026, 10, 19) to 1800L, epochDayOf(2026, 10, 20) to 3600L, epochDayOf(2026, 10, 24) to 7561L),
                items = mapOf("abs_li_fixture01" to 7201L, "abs_li_fixture02" to 3600L),
                totalSeconds = 12961L,
            ),
            stats
        )
    }

    @Test
    fun `an empty or partial response is empty stats`() {
        assertEquals(ServerListeningStats(), ApiJson.decodeFromString<AbsListeningStatsDto>("{}").toServerListeningStats())
        val odd = ApiJson.decodeFromString<AbsListeningStatsDto>("""{"days":{"2026-01-01":-5},"items":{"x":{}}}""")
        assertEquals(ServerListeningStats(), odd.toServerListeningStats())
    }

    @Test
    fun `an item without its own id uses its key`() {
        val dto = ApiJson.decodeFromString<AbsListeningStatsDto>("""{"items":{"li_k":{"timeListening":60}}}""")
        assertEquals(mapOf("abs_li_k" to 60L), dto.toServerListeningStats().items)
    }
}
