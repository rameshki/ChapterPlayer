package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    /** Every row of the book, including the ones waiting to be deleted on the server. */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY timeSec ASC, createdAt ASC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND timeSec = :timeSec")
    suspend fun get(bookId: String, timeSec: Long): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<BookmarkEntity>)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND timeSec = :timeSec")
    suspend fun delete(bookId: String, timeSec: Long)

    /** Removes every row of the book on that server (the first half of replacing them with a merge result, in one transaction). */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND serverId = :serverId")
    suspend fun deleteForBook(bookId: String, serverId: String)

    /** Removes every row of the server (when the server is removed). */
    @Query("DELETE FROM bookmarks WHERE serverId = :serverId")
    suspend fun deleteForServer(serverId: String)

    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId")
    suspend fun getByServer(serverId: String): List<BookmarkEntity>

    /** The rows of the server that the server has not heard yet, oldest first. */
    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId AND state <> 'SYNCED' ORDER BY createdAt ASC, timeSec ASC, bookId ASC")
    suspend fun getPendingByServer(serverId: String): List<BookmarkEntity>

    /** The rows (of any server) the server has not heard yet. */
    @Query("SELECT * FROM bookmarks WHERE state <> 'SYNCED' ORDER BY createdAt ASC, timeSec ASC, bookId ASC")
    fun observePending(): Flow<List<BookmarkEntity>>

    /** Sets [newState] only if the row still has [ifState] and [ifTitle] (it was not changed meanwhile). Returns the rows changed. */
    @Query("UPDATE bookmarks SET state = :newState WHERE bookId = :bookId AND timeSec = :timeSec AND state = :ifState AND title = :ifTitle")
    suspend fun updateStateIf(bookId: String, timeSec: Long, newState: String, ifState: String, ifTitle: String): Int

    /** Removes the row only if it still has [ifState] and [ifTitle]. Returns the rows removed. */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND timeSec = :timeSec AND state = :ifState AND title = :ifTitle")
    suspend fun deleteIf(bookId: String, timeSec: Long, ifState: String, ifTitle: String): Int
}
