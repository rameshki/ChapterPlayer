package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One book's download. [status] is a `DownloadStatus` name. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val bookId: String,
    val serverId: String,
    val status: String,
    val totalBytes: Long,
    val error: String?,
    val updatedAt: Long
)
