package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.LibraryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM libraries ORDER BY serverId ASC, name ASC")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("DELETE FROM libraries WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LibraryEntity>)
}
