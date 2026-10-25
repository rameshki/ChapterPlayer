package com.chapterplayer.data.merge

import com.chapterplayer.data.local.dao.BookMergeDao
import com.chapterplayer.data.local.entity.BookMergeEntity
import com.chapterplayer.domain.browse.MergeOverrides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BookMergeRepositoryTest {

    private class FakeDao : BookMergeDao {
        val rows = MutableStateFlow<List<BookMergeEntity>>(emptyList())
        override fun observeAll(): Flow<List<BookMergeEntity>> = rows
        override suspend fun upsertAll(rows: List<BookMergeEntity>) {
            val byKey = LinkedHashMap<Pair<String, String>, BookMergeEntity>()
            for (r in this.rows.value + rows) byKey[r.bookIdA to r.bookIdB] = r
            this.rows.value = byKey.values.toList()
        }
    }

    private val dao = FakeDao()
    private val repo = RoomBookMergeRepository(dao)

    @Test
    fun `a split keeps the book apart from every other member`() = runTest {
        repo.split("q", listOf("a", "p", "q"))
        assertEquals(MergeOverrides(splits = setOf("a" to "q", "p" to "q")), repo.overrides.first())
    }

    @Test
    fun `a pair is stored once whichever way round`() = runTest {
        repo.merge("z", "b")
        repo.merge("b", "z")
        assertEquals(listOf(BookMergeEntity("b", "z", KIND_MERGE)), dao.rows.value)
    }

    @Test
    fun `the newest choice for a pair wins`() = runTest {
        repo.merge("a", "p")
        repo.split("p", listOf("a"))
        assertEquals(MergeOverrides(splits = setOf("a" to "p")), repo.overrides.first())
        repo.merge("p", "a")
        assertEquals(MergeOverrides(merges = setOf("a" to "p")), repo.overrides.first())
    }

    @Test
    fun `a book is never paired with itself and unknown kinds are ignored`() = runTest {
        repo.merge("a", "a")
        assertEquals(emptyList(), dao.rows.value)
        dao.rows.value = listOf(BookMergeEntity("a", "b", "LATER"))
        assertEquals(MergeOverrides(), repo.overrides.first())
    }
}
