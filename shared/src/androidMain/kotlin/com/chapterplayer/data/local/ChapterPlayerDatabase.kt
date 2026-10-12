package com.chapterplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
abstract class ChapterPlayerDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
    abstract fun serverConfigDao(): ServerConfigDao

    companion object {
        const val DATABASE_NAME = "chapter_player.db"
    }
}
