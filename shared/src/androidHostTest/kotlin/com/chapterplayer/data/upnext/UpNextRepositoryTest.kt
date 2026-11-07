package com.chapterplayer.data.upnext

import com.chapterplayer.data.local.dao.UpNextDao
import com.chapterplayer.data.local.entity.UpNextEntity
import com.chapterplayer.domain.playback.editUpNext
import com.chapterplayer.domain.playback.moveInUpNext
import com.chapterplayer.domain.playback.removeFromUpNext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UpNextRepositoryTest {

    private class FakeDao : UpNextDao {
        val rows = MutableStateFlow<List<UpNextEntity>>(emptyList())
        var replaces = 0
        override fun observeAll(): Flow<List<UpNextEntity>> = rows.map { list -> list.sortedBy { it.position } }
        override suspend fun getAll(): List<UpNextEntity> = rows.value.sortedBy { it.position }
        override suspend fun insertAll(rows: List<UpNextEntity>) {
            val ids = rows.map { it.bookId }.toSet()
            this.rows.value = this.rows.value.filterNot { it.bookId in ids } + rows
        }
        override suspend fun deleteAll() { rows.value = emptyList() }
        override suspend fun deleteFor(bookIds: List<String>) { rows.value = rows.value.filterNot { it.bookId in bookIds } }
        override suspend fun replaceAll(rows: List<UpNextEntity>) {
            replaces++
            this.rows.value = rows
        }
    }

    private val dao = FakeDao()
    private var now = 100L
    private val repo = RoomUpNextRepository(dao) { now }

    @Test
    fun `rows are stored in order with their places and times`() = runTest {
        repo.edit { editUpNext(it, listOf("a"), null, front = false).queue }
        now = 200L
        repo.edit { editUpNext(it, listOf("b"), null, front = true).queue }
        assertEquals(listOf(UpNextEntity("b", 0, 200L), UpNextEntity("a", 1, 100L)), dao.rows.value)
        assertEquals(listOf("b", "a"), repo.queue.first())
    }

    @Test
    fun `a moved row keeps the time it was queued`() = runTest {
        repo.edit { listOf("a", "b") }
        now = 500L
        repo.edit { moveInUpNext(it, "b", -1) }
        assertEquals(listOf(UpNextEntity("b", 0, 100L), UpNextEntity("a", 1, 100L)), dao.rows.value)
    }

    @Test
    fun `nothing is written when nothing changes`() = runTest {
        repo.edit { listOf("a") }
        repo.edit { it }
        repo.edit { editUpNext(it, listOf("a"), null, front = false).queue }
        assertEquals(1, dao.replaces)
    }

    @Test
    fun `a row is stored once`() = runTest {
        repo.edit { listOf("a", "a", "b") }
        assertEquals(listOf("a", "b"), repo.queue.first())
    }

    @Test
    fun `removing and clearing`() = runTest {
        repo.edit { listOf("a", "b", "c") }
        repo.edit { removeFromUpNext(it, listOf("b")) }
        assertEquals(listOf("a", "c"), repo.queue.first())
        repo.edit { emptyList() }
        assertEquals(emptyList(), dao.rows.value)
    }
}
