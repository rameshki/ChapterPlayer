package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.chapterplayer.data.local.entity.UpNextEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UpNextDao {

    @Query("SELECT * FROM up_next ORDER BY position, addedAt")
    fun observeAll(): Flow<List<UpNextEntity>>

    @Query("SELECT * FROM up_next ORDER BY position, addedAt")
    suspend fun getAll(): List<UpNextEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<UpNextEntity>)

    @Query("DELETE FROM up_next")
    suspend fun deleteAll()

    @Query("DELETE FROM up_next WHERE bookId IN (:bookIds)")
    suspend fun deleteFor(bookIds: List<String>)

    /** The whole queue in one go, so a reorder never shows half done. */
    @Transaction
    suspend fun replaceAll(rows: List<UpNextEntity>) {
        deleteAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
