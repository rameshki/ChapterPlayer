package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.dto.AbsMeDto
import com.chapterplayer.data.remote.abs.dto.AbsProgressDto
import com.chapterplayer.domain.playback.ServerProgress
import kotlin.test.Test
import kotlin.test.assertEquals

/** The progress responses, from the synthetic fixtures. */
class AbsProgressParsingTest {

    @Test
    fun `one progress object is read and unknown fields are ignored`() {
        val dto = ApiJson.decodeFromString<AbsProgressDto>(fixture("abs/progress.json"))

        assertEquals("li_fixture01", dto.libraryItemId)
        assertEquals(5400.5, dto.duration)
        assertEquals(1735.25, dto.currentTime)
        assertEquals(1760000000123L, dto.lastUpdate)
        assertEquals(false, dto.isFinished)
    }

    @Test
    fun `seconds become milliseconds and the update time is kept`() {
        val progress = ApiJson.decodeFromString<AbsProgressDto>(fixture("abs/progress.json")).toServerProgress()

        assertEquals(ServerProgress(1_735_250L, 5_400_500L, false, 1760000000123L), progress)
    }

    @Test
    fun `milliseconds are rounded not cut`() {
        val dto = AbsProgressDto("x", duration = 100.0005, currentTime = 10.0006)

        assertEquals(100_001L, dto.toServerProgress().durationMs)
        assertEquals(10_001L, dto.toServerProgress().positionMs)
        assertEquals(10_000L, AbsProgressDto("x", currentTime = 10.0004).toServerProgress().positionMs)
    }

    @Test
    fun `the me response gives only the progress list`() {
        val me = ApiJson.decodeFromString<AbsMeDto>(fixture("abs/me.json"))

        assertEquals(listOf("li_fixture01", "li_fixture02", "li_fixture03"), me.mediaProgress.map { it.libraryItemId })
        val finished = me.mediaProgress[1].toServerProgress()
        assertEquals(ServerProgress(3_600_000L, 3_600_000L, true, 1760000500000L), finished)
        // Missing fields take their defaults.
        assertEquals(false, me.mediaProgress[2].isFinished)
    }

    @Test
    fun `a me response without progress is an empty list`() {
        assertEquals(emptyList(), ApiJson.decodeFromString<AbsMeDto>("""{"id":"u"}""").mediaProgress)
    }
}
