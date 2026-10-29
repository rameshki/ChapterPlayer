package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.chapterplayer.data.local.entity.BookSecondsRow
import com.chapterplayer.data.local.entity.ImportedListeningDayEntity
import com.chapterplayer.data.local.entity.ImportedListeningItemEntity
import com.chapterplayer.data.local.entity.ListeningImportEntity
import com.chapterplayer.data.local.entity.ListeningLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningDao {

    @Query("SELECT * FROM listening_log ORDER BY day, bookId")
    fun observeLog(): Flow<List<ListeningLogEntity>>

    /** Adds to an existing row; the number of rows changed (0 when the day and book have none yet). */
    @Query(
        "UPDATE listening_log SET wallSeconds = wallSeconds + :wallSeconds, bookSeconds = bookSeconds + :bookSeconds " +
            "WHERE day = :day AND bookId = :bookId"
    )
    suspend fun addTo(day: Int, bookId: String, wallSeconds: Long, bookSeconds: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLog(row: ListeningLogEntity): Long

    /** Each book's logged wall seconds over all days. */
    @Query("SELECT bookId, SUM(wallSeconds) AS seconds FROM listening_log GROUP BY bookId")
    suspend fun loggedTotals(): List<BookSecondsRow>

    @Query("SELECT * FROM imported_listening_days ORDER BY day, serverId")
    fun observeImportedDays(): Flow<List<ImportedListeningDayEntity>>

    @Query("SELECT * FROM imported_listening_items")
    fun observeImportedItems(): Flow<List<ImportedListeningItemEntity>>

    @Query("SELECT serverId FROM listening_imports")
    suspend fun importedServerIds(): List<String>

    @Query("DELETE FROM imported_listening_days WHERE serverId = :serverId")
    suspend fun deleteImportedDays(serverId: String)

    @Query("DELETE FROM imported_listening_items WHERE serverId = :serverId")
    suspend fun deleteImportedItems(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedDays(rows: List<ImportedListeningDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedItems(rows: List<ImportedListeningItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImport(row: ListeningImportEntity)
}
