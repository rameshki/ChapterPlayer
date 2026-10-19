package com.chapterplayer.data

import com.chapterplayer.data.model.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadStatesTest {
    @Test
    fun `no download states emits an empty map`() = runTest {
        assertTrue(NoDownloadStates().observe().first().isEmpty())
    }

    @Test
    fun `a book missing from the map is not downloaded`() {
        assertEquals(DownloadState.NOT_DOWNLOADED, emptyMap<String, DownloadState>().stateOf("a"))
    }

    @Test
    fun `a book in the map returns its state`() {
        val map = mapOf("a" to DownloadState.DOWNLOADED, "b" to DownloadState.DOWNLOADING)
        assertEquals(DownloadState.DOWNLOADED, map.stateOf("a"))
        assertEquals(DownloadState.DOWNLOADING, map.stateOf("b"))
        assertEquals(DownloadState.NOT_DOWNLOADED, map.stateOf("c"))
    }
}
