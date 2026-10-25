package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Listening this app recorded: one row per local day ([day] is an epoch day in the device's zone) and book.
 * [wallSeconds] is real time, [bookSeconds] the book time it covered (more than the wall time above 1x).
 * [source] is the [com.chapterplayer.data.model.ServerSource] name of the book's server when it was played.
 */
@Entity(tableName = "listening_log", primaryKeys = ["day", "bookId"])
data class ListeningLogEntity(
    val day: Int,
    val bookId: String,
    val serverId: String,
    val source: String,
    val wallSeconds: Long,
    val bookSeconds: Long,
)

/** A daily total from a server's own history; used only for days that have no [ListeningLogEntity] rows. */
@Entity(tableName = "imported_listening_days", primaryKeys = ["serverId", "day"])
data class ImportedListeningDayEntity(
    val serverId: String,
    val day: Int,
    val seconds: Long,
)

/** A book's all-time total from a server's own history (no dates: the server does not break it down by day). */
@Entity(tableName = "imported_listening_items", primaryKeys = ["serverId", "bookId"])
data class ImportedListeningItemEntity(
    val serverId: String,
    val bookId: String,
    val seconds: Long,
)

/** When a server's history was last imported; a server without a row has never been imported. */
@Entity(tableName = "listening_imports")
data class ListeningImportEntity(
    @PrimaryKey val serverId: String,
    val importedAt: Long,
)
