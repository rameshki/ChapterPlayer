package com.chapterplayer.data.local.entity

import androidx.room.Entity

/** A server-side collection. Its books are in [CollectionBookEntity]. */
@Entity(tableName = "collections", primaryKeys = ["serverId", "id"])
data class CollectionEntity(
    val serverId: String,
    val id: String,
    val name: String
)
