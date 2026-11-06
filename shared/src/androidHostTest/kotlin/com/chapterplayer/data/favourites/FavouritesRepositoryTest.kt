package com.chapterplayer.data.favourites

import com.chapterplayer.data.local.dao.FavouriteDao
import com.chapterplayer.data.local.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavouritesRepositoryTest {

    private class FakeDao : FavouriteDao {
        val rows = MutableStateFlow<List<FavouriteEntity>>(emptyList())
        override fun observeIds(): Flow<List<String>> = rows.map { list -> list.map { it.bookId } }
        override suspend fun insertAll(rows: List<FavouriteEntity>) {
            val known = this.rows.value.map { it.bookId }.toSet()
            this.rows.value = this.rows.value + rows.filter { it.bookId !in known }
        }
        override suspend fun existing(bookIds: List<String>): List<String> = rows.value.map { it.bookId }.filter { it in bookIds }
        override suspend fun deleteAll(bookIds: List<String>) {
            rows.value = rows.value.filterNot { it.bookId in bookIds }
        }
    }

    private val dao = FakeDao()
    private var now = 100L
    private val repo = RoomFavouritesRepository(dao) { now }

    @Test
    fun `adding stores every id once with the time`() = runTest {
        repo.add(listOf("a", "p", "a"))
        assertEquals(listOf(FavouriteEntity("a", 100L), FavouriteEntity("p", 100L)), dao.rows.value)
        assertEquals(setOf("a", "p"), repo.favouriteIds.first())
    }

    @Test
    fun `adding again keeps the first date`() = runTest {
        repo.add(listOf("a"))
        now = 200L
        repo.add(listOf("a", "b"))
        assertEquals(listOf(FavouriteEntity("a", 100L), FavouriteEntity("b", 200L)), dao.rows.value)
    }

    @Test
    fun `toggling a merged book with one favourite copy removes every copy`() = runTest {
        repo.add(listOf("p"))
        assertFalse(repo.toggle(listOf("a", "p")))
        assertEquals(emptySet(), repo.favouriteIds.first())
    }

    @Test
    fun `toggling a book that is no favourite marks every copy`() = runTest {
        assertTrue(repo.toggle(listOf("a", "p")))
        assertEquals(setOf("a", "p"), repo.favouriteIds.first())
    }

    @Test
    fun `removing leaves other favourites alone`() = runTest {
        repo.add(listOf("a", "b"))
        repo.remove(listOf("a", "x"))
        assertEquals(setOf("b"), repo.favouriteIds.first())
    }

    @Test
    fun `toggling nothing changes nothing`() = runTest {
        assertFalse(repo.toggle(emptyList()))
        assertEquals(emptyList(), dao.rows.value)
    }
}
