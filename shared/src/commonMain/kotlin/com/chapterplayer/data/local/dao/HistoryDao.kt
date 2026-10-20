package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.chapterplayer.data.local.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    /** The book's positions, newest first. */
    @Query("SELECT * FROM position_history WHERE bookId = :bookId ORDER BY recordedAt DESC, id DESC")
    fun observeForBook(bookId: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM position_history WHERE bookId = :bookId ORDER BY recordedAt DESC, id DESC")
    suspend fun getForBook(bookId: String): List<HistoryEntity>

    @Insert
    suspend fun insert(row: HistoryEntity): Long

    @Query("DELETE FROM position_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM position_history WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)
}
