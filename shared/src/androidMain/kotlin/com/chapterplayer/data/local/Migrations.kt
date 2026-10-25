package com.chapterplayer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Before version 9 the app stored a Plex music section's ARTISTS as books (no author, length or audio), with the
 * token in their cover and stream addresses. Those rows are dropped; with nothing cached for its Plex servers the
 * app syncs them again at start, now as albums with token-free addresses. No other table holds Plex rows (Plex books
 * could not play, download or bookmark), and the schema itself does not change.
 */
internal const val DROP_PLEX_BOOKS_SQL = "DELETE FROM books WHERE source = 'PLEX'"

/** Version 8 to 9: the data-only clean-up described at [DROP_PLEX_BOOKS_SQL]. */
internal object Migration8To9 : Migration(8, 9) {
    // Room calls one or the other depending on whether a driver is set; both do the same.
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(DROP_PLEX_BOOKS_SQL)

    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(DROP_PLEX_BOOKS_SQL)
}
