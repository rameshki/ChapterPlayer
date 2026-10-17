package com.chapterplayer.data.local.entity

import androidx.room.Entity

/** A library the user turned off on a server. Rows exist only for turned-off libraries. */
@Entity(tableName = "excluded_libraries", primaryKeys = ["serverId", "libraryId"])
data class ExcludedLibraryEntity(
    val serverId: String,
    val libraryId: String
)
