package com.chapterplayer.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.chapterplayer.data.PathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The database on iOS: the bundled SQLite driver (the same SQLite on every iOS version), queries on the IO dispatcher,
 * the file in the app's folder. The schema and its migrations are the common ones.
 */
fun createIosDatabase(paths: PathProvider): ChapterPlayerDatabase =
    Room.databaseBuilder<ChapterPlayerDatabase>(name = paths.databaseFile(ChapterPlayerDatabase.DATABASE_NAME))
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(DropPlexRowsMigration())
        .build()
