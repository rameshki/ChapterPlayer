package com.chapterplayer.data.local.entity

import androidx.room.Entity

/** One book in a collection. [position] keeps the order the server listed the books in. */
@Entity(tableName = "collection_books", primaryKeys = ["serverId", "collectionId", "bookId"])
data class CollectionBookEntity(
    val serverId: String,
    val collectionId: String,
    val bookId: String,
    val position: Int
)
