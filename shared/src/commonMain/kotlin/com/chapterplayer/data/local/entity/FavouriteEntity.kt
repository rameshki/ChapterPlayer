package com.chapterplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A book the person marked as a favourite on this phone. Favourites are local only: Audiobookshelf has no favourites
 * to sync with, so they do not follow the person to another device. A book several servers have is a favourite when
 * any of its copies has a row (see `isFavourite`); a row whose book is gone stays and is simply never shown.
 */
@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val bookId: String,
    /** When it was marked, in epoch milliseconds. */
    val addedAt: Long,
)
