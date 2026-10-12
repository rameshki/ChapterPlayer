package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE source = :source ORDER BY title ASC")
    fun observeBySource(source: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE serverId = :serverId ORDER BY title ASC")
    fun observeByServer(serverId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    /** Remove all cached books from a specific server. Called before re-syncing. */
    @Query("DELETE FROM books WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    /** Returns the timestamp of the most recently cached book for a server. */
    @Query("SELECT MAX(cachedAt) FROM books WHERE serverId = :serverId")
    suspend fun getLastCacheTime(serverId: String): Long?
}
