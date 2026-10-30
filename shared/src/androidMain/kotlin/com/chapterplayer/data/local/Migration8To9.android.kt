package com.chapterplayer.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 8 to 9 on Android: the common [DropPlexRowsMigration] plus the SupportSQLiteDatabase overload, which Room calls
 * because the Android database has no SQLite driver set. Both run [DROP_PLEX_ROWS_SQL].
 */
internal object Migration8To9 : DropPlexRowsMigration() {
    override fun migrate(db: SupportSQLiteDatabase) = DROP_PLEX_ROWS_SQL.forEach { db.execSQL(it) }
}
