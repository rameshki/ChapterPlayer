package com.chapterplayer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One audio file of a download; [ino] is the server's file id. [durationMs] (0 when not known) and [mimeType]
 * ("" when not known) let a downloaded book play without the server.
 */
@Entity(tableName = "download_files", primaryKeys = ["bookId", "ino"])
data class DownloadFileEntity(
    val bookId: String,
    val ino: String,
    val fileName: String,
    val path: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    @ColumnInfo(defaultValue = "''") val mimeType: String = ""
)
