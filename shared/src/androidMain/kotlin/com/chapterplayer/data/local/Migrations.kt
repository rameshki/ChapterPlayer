package com.chapterplayer.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Before version 9 the app stored a Plex music section's ARTISTS as books (no author, length or audio), with the
 * token in their cover and stream addresses. Those book rows are dropped, and so is anything keyed by a Plex book id
 * (`plex_...`): local progress, queued progress, position history and bookmarks. They belong to artist ids that no
 * longer exist as books, and the new book ids are album ids. With nothing cached for its Plex servers the app syncs
 * them again at start, now as albums with token-free addresses. The schema itself does not change.
 */
internal val DROP_PLEX_ROWS_SQL: List<String> = listOf(
    "DELETE FROM books WHERE source = 'PLEX'",
    "DELETE FROM progress WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
    "DELETE FROM pending_progress WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
    "DELETE FROM position_history WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
    "DELETE FROM bookmarks WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
)

/** Version 8 to 9: the data-only clean-up described at [DROP_PLEX_ROWS_SQL]. */
internal object Migration8To9 : Migration(8, 9) {
    // Room calls one or the other depending on whether a driver is set; both do the same.
    override fun migrate(connection: SQLiteConnection) = DROP_PLEX_ROWS_SQL.forEach { connection.execSQL(it) }

    override fun migrate(db: SupportSQLiteDatabase) = DROP_PLEX_ROWS_SQL.forEach { db.execSQL(it) }
}
