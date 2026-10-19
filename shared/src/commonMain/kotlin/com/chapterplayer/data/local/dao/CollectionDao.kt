package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection_books ORDER BY position ASC")
    fun observeCollectionBooks(): Flow<List<CollectionBookEntity>>

    @Query("DELETE FROM collections WHERE serverId = :serverId")
    suspend fun deleteCollectionsForServer(serverId: String)

    @Query("DELETE FROM collection_books WHERE serverId = :serverId")
    suspend fun deleteCollectionBooksForServer(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollections(rows: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionBooks(rows: List<CollectionBookEntity>)
}
