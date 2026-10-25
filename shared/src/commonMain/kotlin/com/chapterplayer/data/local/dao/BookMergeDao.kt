package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.BookMergeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookMergeDao {

    @Query("SELECT * FROM book_merges")
    fun observeAll(): Flow<List<BookMergeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BookMergeEntity>)

    @Query("DELETE FROM book_merges WHERE bookIdA = :a AND bookIdB = :b AND kind = :kind")
    suspend fun deleteKind(a: String, b: String, kind: String)

    @Query("DELETE FROM book_merges WHERE bookIdA IN (:bookIds) OR bookIdB IN (:bookIds)")
    suspend fun deleteForBooks(bookIds: List<String>)
}
