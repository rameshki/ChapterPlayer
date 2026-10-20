package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.PendingProgressDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.entity.PendingProgressEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Progress fake: REPLACE on the primary key and the same filters and order as the real queries. */
class InMemoryProgressDao(initial: List<ProgressEntity> = emptyList()) : ProgressDao {
    val rows = MutableStateFlow(initial)
    override fun observe(bookId: String): Flow<ProgressEntity?> = rows.map { it.firstOrNull { r -> r.bookId == bookId } }
    override suspend fun get(bookId: String): ProgressEntity? = rows.value.firstOrNull { it.bookId == bookId }
    override fun observeInProgress(): Flow<List<ProgressEntity>> =
        rows.map { it.filter { r -> r.positionMs > 0 && !r.isFinished }.sortedByDescending { r -> r.lastPlayedAt } }
    override fun observeAll(): Flow<List<ProgressEntity>> = rows
    override suspend fun upsert(progress: ProgressEntity) {
        rows.value = rows.value.filter { it.bookId != progress.bookId } + progress
    }
    override suspend fun delete(bookId: String) { rows.value = rows.value.filter { it.bookId != bookId } }
}

/** Pending queue fake: REPLACE on the primary key, getAll ordered by updatedAtMs, update touches an existing row only. */
class InMemoryPendingProgressDao(initial: List<PendingProgressEntity> = emptyList()) : PendingProgressDao {
    val rows = MutableStateFlow(initial)
    override suspend fun upsert(row: PendingProgressEntity) {
        rows.value = rows.value.filter { it.bookId != row.bookId } + row
    }
    override suspend fun get(bookId: String): PendingProgressEntity? = rows.value.firstOrNull { it.bookId == bookId }
    override suspend fun getAll(): List<PendingProgressEntity> = rows.value.sortedWith(compareBy({ it.updatedAtMs }, { it.bookId }))
    override fun observeCount(): Flow<Int> = rows.map { it.size }
    override suspend fun delete(bookId: String, ifUpdatedAtMs: Long?) {
        rows.value = rows.value.filter { !(it.bookId == bookId && (ifUpdatedAtMs == null || it.updatedAtMs == ifUpdatedAtMs)) }
    }
    override suspend fun updateAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long?) {
        rows.value = rows.value.map {
            if (it.bookId == bookId && (ifUpdatedAtMs == null || it.updatedAtMs == ifUpdatedAtMs)) it.copy(attempts = attempts, nextAttemptAtMs = nextAttemptAtMs) else it
        }
    }
}
