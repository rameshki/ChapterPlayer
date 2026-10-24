package com.chapterplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download_files ORDER BY bookId, sortOrder")
    fun observeFiles(): Flow<List<DownloadFileEntity>>

    @Query("SELECT * FROM download_files WHERE bookId = :bookId ORDER BY sortOrder")
    suspend fun getFiles(bookId: String): List<DownloadFileEntity>

    @Query("SELECT * FROM downloads WHERE bookId = :bookId")
    suspend fun get(bookId: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFiles(files: List<DownloadFileEntity>)

    @Query("UPDATE downloads SET status = :status, error = :error, updatedAt = :updatedAt WHERE bookId = :bookId")
    suspend fun updateStatus(bookId: String, status: String, error: String?, updatedAt: Long)

    /** Sets the status only while it is one of [from]; returns how many rows changed (0: someone else changed it first). */
    @Query("UPDATE downloads SET status = :status, error = :error, updatedAt = :updatedAt WHERE bookId = :bookId AND status IN (:from)")
    suspend fun updateStatusIf(bookId: String, from: List<String>, status: String, error: String?, updatedAt: Long): Int

    @Query("UPDATE download_files SET doneBytes = :doneBytes WHERE bookId = :bookId AND ino = :ino")
    suspend fun updateFileProgress(bookId: String, ino: String, doneBytes: Long)

    @Query("DELETE FROM downloads WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    @Query("DELETE FROM download_files WHERE bookId = :bookId")
    suspend fun deleteFiles(bookId: String)

    /** The download and its files go together. */
    @Transaction
    suspend fun deleteBook(bookId: String) {
        deleteFiles(bookId)
        delete(bookId)
    }
}
