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
}
