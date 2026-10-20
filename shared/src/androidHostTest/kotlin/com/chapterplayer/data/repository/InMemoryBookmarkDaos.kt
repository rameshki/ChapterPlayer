package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.BookmarkDao
import com.chapterplayer.data.local.dao.HistoryDao
import com.chapterplayer.data.local.entity.BookmarkEntity
import com.chapterplayer.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Bookmark fake: REPLACE on (bookId, timeSec) and the same filters, order and conditions as the real queries. */
class InMemoryBookmarkDao(initial: List<BookmarkEntity> = emptyList()) : BookmarkDao {
    val rows = MutableStateFlow(initial)
    private fun same(r: BookmarkEntity, bookId: String, timeSec: Long) = r.bookId == bookId && r.timeSec == timeSec

    override fun observeForBook(bookId: String): Flow<List<BookmarkEntity>> =
        rows.map { it.filter { r -> r.bookId == bookId }.sortedWith(compareBy({ r -> r.timeSec }, { r -> r.createdAt })) }
    override suspend fun get(bookId: String, timeSec: Long): BookmarkEntity? = rows.value.firstOrNull { same(it, bookId, timeSec) }
    override suspend fun upsert(row: BookmarkEntity) {
        rows.value = rows.value.filterNot { same(it, row.bookId, row.timeSec) } + row
    }
    override suspend fun insertAll(rows: List<BookmarkEntity>) { for (row in rows) upsert(row) }
    override suspend fun delete(bookId: String, timeSec: Long) { rows.value = rows.value.filterNot { same(it, bookId, timeSec) } }
    override suspend fun deleteForBook(bookId: String) { rows.value = rows.value.filter { it.bookId != bookId } }
    override suspend fun getByServer(serverId: String): List<BookmarkEntity> = rows.value.filter { it.serverId == serverId }
    override suspend fun getPendingByServer(serverId: String): List<BookmarkEntity> =
        rows.value.filter { it.serverId == serverId && it.state != "SYNCED" }
            .sortedWith(compareBy({ it.createdAt }, { it.timeSec }, { it.bookId }))
    override suspend fun updateStateIf(bookId: String, timeSec: Long, newState: String, ifState: String, ifTitle: String): Int {
        var changed = 0
        rows.value = rows.value.map {
            if (same(it, bookId, timeSec) && it.state == ifState && it.title == ifTitle) { changed++; it.copy(state = newState) } else it
        }
        return changed
    }
    override suspend fun deleteIf(bookId: String, timeSec: Long, ifState: String, ifTitle: String): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { same(it, bookId, timeSec) && it.state == ifState && it.title == ifTitle }
        return before - rows.value.size
    }
}

/** History fake: auto ids from 1, newest first by recordedAt then id. */
class InMemoryHistoryDao(initial: List<HistoryEntity> = emptyList()) : HistoryDao {
    val rows = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
    private fun ordered(bookId: String) =
        rows.value.filter { it.bookId == bookId }.sortedWith(compareByDescending<HistoryEntity> { it.recordedAt }.thenByDescending { it.id })

    override fun observeForBook(bookId: String): Flow<List<HistoryEntity>> = rows.map { _ -> ordered(bookId) }
    override suspend fun getForBook(bookId: String): List<HistoryEntity> = ordered(bookId)
    override suspend fun insert(row: HistoryEntity): Long {
        val id = nextId++
        rows.value = rows.value + row.copy(id = id)
        return id
    }
    override suspend fun deleteByIds(ids: List<Long>) { rows.value = rows.value.filter { it.id !in ids } }
    override suspend fun deleteForBook(bookId: String) { rows.value = rows.value.filter { it.bookId != bookId } }
}
