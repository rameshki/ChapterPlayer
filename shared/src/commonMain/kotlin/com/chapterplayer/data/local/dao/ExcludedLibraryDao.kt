package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.ExcludedLibraryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExcludedLibraryDao {

    @Query("SELECT * FROM excluded_libraries")
    fun observeAll(): Flow<List<ExcludedLibraryEntity>>

    @Query("SELECT * FROM excluded_libraries")
    suspend fun getAll(): List<ExcludedLibraryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ExcludedLibraryEntity>)

    @Query("DELETE FROM excluded_libraries WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)
}
