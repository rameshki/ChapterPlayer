package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.chapterplayer.data.local.entity.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {

    @Query("SELECT * FROM server_configs ORDER BY name ASC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs")
    suspend fun getAll(): List<ServerConfigEntity>

    @Query("SELECT * FROM server_configs WHERE id = :id")
    suspend fun getById(id: String): ServerConfigEntity?

    @Query("SELECT * FROM server_configs WHERE source = :source")
    fun observeBySource(source: String): Flow<List<ServerConfigEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ServerConfigEntity)

    @Update
    suspend fun update(config: ServerConfigEntity)

    @Delete
    suspend fun delete(config: ServerConfigEntity)

    @Query("DELETE FROM server_configs WHERE id = :id")
    suspend fun deleteById(id: String)
}
