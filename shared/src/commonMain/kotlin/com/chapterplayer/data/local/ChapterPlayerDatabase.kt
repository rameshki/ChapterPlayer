package com.chapterplayer.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.local.entity.ServerConfigEntity

@Database(
    entities = [
        BookEntity::class,
        ProgressEntity::class,
        ServerConfigEntity::class
    ],
    version = 1,
    exportSchema = true
)
@ConstructedBy(ChapterPlayerDatabaseConstructor::class)
abstract class ChapterPlayerDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
    abstract fun serverConfigDao(): ServerConfigDao

    companion object {
        const val DATABASE_NAME = "chapter_player.db"
    }
}

/** Room generates the actual for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object ChapterPlayerDatabaseConstructor : RoomDatabaseConstructor<ChapterPlayerDatabase> {
    override fun initialize(): ChapterPlayerDatabase
}
