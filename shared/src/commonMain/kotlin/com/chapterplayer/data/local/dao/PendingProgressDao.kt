package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.PendingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingProgressEntity)

    @Query("SELECT * FROM pending_progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): PendingProgressEntity?

    /** Every queued row, oldest position first (rows with the same time in book id order). */
    @Query("SELECT * FROM pending_progress ORDER BY updatedAtMs ASC, bookId ASC")
    suspend fun getAll(): List<PendingProgressEntity>

    @Query("SELECT COUNT(*) FROM pending_progress")
    fun observeCount(): Flow<Int>

    /** Removes the row of [bookId]; when [ifUpdatedAtMs] is given only if the row still has that time (it was not queued again meanwhile). */
    @Query("DELETE FROM pending_progress WHERE bookId = :bookId AND (:ifUpdatedAtMs IS NULL OR updatedAtMs = :ifUpdatedAtMs)")
    suspend fun delete(bookId: String, ifUpdatedAtMs: Long?)

    /** Records a failed delivery. Changes nothing when the row is gone, or (with [ifUpdatedAtMs]) was queued again meanwhile. */
    @Query("UPDATE pending_progress SET attempts = :attempts, nextAttemptAtMs = :nextAttemptAtMs WHERE bookId = :bookId AND (:ifUpdatedAtMs IS NULL OR updatedAtMs = :ifUpdatedAtMs)")
    suspend fun updateAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long, ifUpdatedAtMs: Long?)
}
