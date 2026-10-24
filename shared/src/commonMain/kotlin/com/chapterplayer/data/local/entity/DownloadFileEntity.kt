package com.chapterplayer.data.local.entity

import androidx.room.Entity

/** One audio file of a download; [ino] is the server's file id. */
@Entity(tableName = "download_files", primaryKeys = ["bookId", "ino"])
data class DownloadFileEntity(
    val bookId: String,
    val ino: String,
    val fileName: String,
    val path: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val sortOrder: Int
)
