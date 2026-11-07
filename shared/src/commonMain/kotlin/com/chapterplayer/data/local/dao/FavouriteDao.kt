package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.FavouriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavouriteDao {

    @Query("SELECT bookId FROM favourites")
    fun observeIds(): Flow<List<String>>

    /** A book that already is a favourite keeps its first date. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<FavouriteEntity>)

    @Query("SELECT bookId FROM favourites WHERE bookId IN (:bookIds)")
    suspend fun existing(bookIds: List<String>): List<String>

    @Query("DELETE FROM favourites WHERE bookId IN (:bookIds)")
    suspend fun deleteAll(bookIds: List<String>)
}
