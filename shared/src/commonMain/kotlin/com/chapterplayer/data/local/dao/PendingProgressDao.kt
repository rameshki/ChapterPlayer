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

    /** Every queued row, oldest position first. */
    @Query("SELECT * FROM pending_progress ORDER BY updatedAtMs ASC")
    suspend fun getAll(): List<PendingProgressEntity>

    @Query("SELECT COUNT(*) FROM pending_progress")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM pending_progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    /** Records a failed delivery. Changes nothing when the row is gone. */
    @Query("UPDATE pending_progress SET attempts = :attempts, nextAttemptAtMs = :nextAttemptAtMs WHERE bookId = :bookId")
    suspend fun updateAttempt(bookId: String, attempts: Int, nextAttemptAtMs: Long)
}
