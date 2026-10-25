package com.chapterplayer.data.local.entity

import androidx.room.Entity

/**
 * One correction of the automatic "same book on several servers" matching: [kind] is "SPLIT" (not the same book)
 * or "MERGE" (the same book although the titles differ). The pair is stored with [bookIdA] < [bookIdB], so a pair
 * has one row and a new choice replaces the old one.
 */
@Entity(tableName = "book_merges", primaryKeys = ["bookIdA", "bookIdB"])
data class BookMergeEntity(
    val bookIdA: String,
    val bookIdB: String,
    val kind: String
)
