package com.chapterplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AbsProgressPatchesTest {

    @Test
    fun `an ordinary place not finished is one patch with the time and the fraction`() {
        assertEquals(listOf(AbsProgressPatch(1_350.0, 5_400.0, 0.25, false)), absProgressPatches(1_350_000, 5_400_000, false))
    }

    @Test
    fun `an explicit not finished past the start first sets not finished without a time then the place`() {
        // a finished item set not finished is reset to 0 and the time sent with it is dropped: the place goes second
        assertEquals(
            listOf(AbsProgressPatch(null, 5_400.0, 0.25, false), AbsProgressPatch(1_350.0, 5_400.0, 0.25, false)),
            absProgressPatches(1_350_000, 5_400_000, false, setNotFinished = true),
        )
        assertEquals(listOf(AbsProgressPatch(0.0, 5_400.0, 0.0, false)), absProgressPatches(0, 5_400_000, false, setNotFinished = true))
    }

    @Test
    fun `not finished at zero is one patch at zero`() {
        assertEquals(listOf(AbsProgressPatch(0.0, 5_400.0, 0.0, false)), absProgressPatches(0, 5_400_000, false))
    }

    @Test
    fun `finished past the start is one patch with that time as before`() {
        assertEquals(listOf(AbsProgressPatch(5_398.0, 5_400.0, 5_398.0 / 5_400.0, true)), absProgressPatches(5_398_000, 5_400_000, true))
    }

    @Test
    fun `finished at zero first moves the time to zero then sets finished without a time`() {
        assertEquals(
            listOf(AbsProgressPatch(0.0, 5_400.0, 0.0, false), AbsProgressPatch(null, 5_400.0, 1.0, true)),
            absProgressPatches(0, 5_400_000, true),
        )
    }

    @Test
    fun `an unknown length sends a zero fraction and never a negative time`() {
        assertEquals(listOf(AbsProgressPatch(0.0, 0.0, 0.0, false), AbsProgressPatch(null, 0.0, 1.0, true)), absProgressPatches(-5, 0, true))
    }
}
