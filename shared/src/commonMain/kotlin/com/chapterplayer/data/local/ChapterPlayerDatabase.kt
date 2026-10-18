package com.chapterplayer.data.local

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.CollectionDao
import com.chapterplayer.data.local.dao.ExcludedLibraryDao
import com.chapterplayer.data.local.dao.LibraryDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.ExcludedLibraryEntity
import com.chapterplayer.data.local.entity.LibraryEntity
import com.chapterplayer.data.local.entity.ProgressEntity
import com.chapterplayer.data.local.entity.ServerConfigEntity

@Database(
    entities = [
        BookEntity::class,
        ProgressEntity::class,
        ServerConfigEntity::class,
        ExcludedLibraryEntity::class,
        LibraryEntity::class,
        CollectionEntity::class,
        CollectionBookEntity::class
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true
)
@ConstructedBy(ChapterPlayerDatabaseConstructor::class)
abstract class ChapterPlayerDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun progressDao(): ProgressDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun excludedLibraryDao(): ExcludedLibraryDao
    abstract fun libraryDao(): LibraryDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        const val DATABASE_NAME = "chapter_player.db"
    }
}

/** Room generates the actual for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object ChapterPlayerDatabaseConstructor : RoomDatabaseConstructor<ChapterPlayerDatabase> {
    override fun initialize(): ChapterPlayerDatabase
}
