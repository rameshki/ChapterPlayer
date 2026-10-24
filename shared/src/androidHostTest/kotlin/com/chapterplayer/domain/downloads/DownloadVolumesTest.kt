package com.chapterplayer.domain.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadVolumesTest {

    private val internal = DownloadVolume(INTERNAL_VOLUME_ID, "Internal storage", 10)
    private val sd = DownloadVolume("external:/sd", "SD card", 20)

    @Test
    fun `the chosen volume is used while it is there, internal storage otherwise`() {
        assertEquals(internal, chooseVolume(null, listOf(internal, sd)))
        assertEquals(sd, chooseVolume("external:/sd", listOf(internal, sd)))
        assertEquals(internal, chooseVolume("external:/gone", listOf(internal, sd)))
        assertEquals(null, chooseVolume("external:/sd", emptyList()))
    }

    @Test
    fun `external volumes are named by kind and numbered when there are several SD cards`() {
        assertEquals(listOf("Phone storage (app folder)"), externalVolumeLabels(listOf(false)))
        assertEquals(listOf("Phone storage (app folder)", "SD card"), externalVolumeLabels(listOf(false, true)))
        assertEquals(listOf("Phone storage (app folder)", "SD card 1", "SD card 2"), externalVolumeLabels(listOf(false, true, true)))
    }
}
