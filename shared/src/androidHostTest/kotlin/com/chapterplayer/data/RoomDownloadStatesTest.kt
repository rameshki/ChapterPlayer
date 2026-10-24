package com.chapterplayer.data

import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.model.DownloadState
import com.chapterplayer.data.repository.InMemoryDownloadDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomDownloadStatesTest {
    private fun row(id: String, status: String) = DownloadEntity(id, "s1", status, 100, null, 1)

    @Test
    fun `done is downloaded and every other status is downloading`() = runTest {
        val dao = InMemoryDownloadDao(
            listOf(row("a", "DONE"), row("b", "QUEUED"), row("c", "DOWNLOADING"), row("d", "PAUSED"), row("e", "FAILED"))
        )
        assertEquals(
            mapOf(
                "a" to DownloadState.DOWNLOADED, "b" to DownloadState.DOWNLOADING, "c" to DownloadState.DOWNLOADING,
                "d" to DownloadState.DOWNLOADING, "e" to DownloadState.DOWNLOADING,
            ),
            RoomDownloadStates(dao).observe().first()
        )
    }

    @Test
    fun `a book with no row is missing from the map`() = runTest {
        val states = RoomDownloadStates(InMemoryDownloadDao(listOf(row("a", "DONE")))).observe().first()
        assertEquals(DownloadState.NOT_DOWNLOADED, states.stateOf("zzz"))
        assertEquals(1, states.size)
    }

    @Test
    fun `an unknown status is treated as downloading`() = runTest {
        val states = RoomDownloadStates(InMemoryDownloadDao(listOf(row("a", "WEIRD")))).observe().first()
        assertEquals(DownloadState.DOWNLOADING, states["a"])
    }

    @Test
    fun `the map follows the table`() = runTest {
        val dao = InMemoryDownloadDao()
        val states = RoomDownloadStates(dao)
        assertEquals(emptyMap(), states.observe().first())
        dao.upsert(row("a", "DOWNLOADING"))
        assertEquals(DownloadState.DOWNLOADING, states.observe().first()["a"])
        dao.updateStatus("a", "DONE", null, 2)
        assertEquals(DownloadState.DOWNLOADED, states.observe().first()["a"])
        dao.deleteBook("a")
        assertEquals(emptyMap(), states.observe().first())
    }
}
