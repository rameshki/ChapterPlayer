package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ProgressEntity?

    /** Returns in-progress books (started but not finished), most recent first. */
    @Query("""
        SELECT * FROM progress
        WHERE positionMs > 0 AND isFinished = 0
        ORDER BY lastPlayedAt DESC
    """)
    fun observeInProgress(): Flow<List<ProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}
