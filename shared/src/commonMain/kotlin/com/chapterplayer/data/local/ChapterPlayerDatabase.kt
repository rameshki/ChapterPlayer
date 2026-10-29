package com.chapterplayer.data.local

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.BookMergeDao
import com.chapterplayer.data.local.dao.BookmarkDao
import com.chapterplayer.data.local.dao.CollectionDao
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.local.dao.ExcludedLibraryDao
import com.chapterplayer.data.local.dao.HistoryDao
import com.chapterplayer.data.local.dao.LibraryDao
import com.chapterplayer.data.local.dao.ListeningDao
import com.chapterplayer.data.local.dao.PendingProgressDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.BookEntity
import com.chapterplayer.data.local.entity.BookMergeEntity
import com.chapterplayer.data.local.entity.BookmarkEntity
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.DownloadEntity
import com.chapterplayer.data.local.entity.DownloadFileEntity
import com.chapterplayer.data.local.entity.ExcludedLibraryEntity
import com.chapterplayer.data.local.entity.HistoryEntity
import com.chapterplayer.data.local.entity.ImportedListeningDayEntity
import com.chapterplayer.data.local.entity.ImportedListeningItemEntity
import com.chapterplayer.data.local.entity.LibraryEntity
import com.chapterplayer.data.local.entity.ListeningImportEntity
import com.chapterplayer.data.local.entity.ListeningLogEntity
import com.chapterplayer.data.local.entity.PendingProgressEntity
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
        CollectionBookEntity::class,
        PendingProgressEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        DownloadFileEntity::class,
        BookMergeEntity::class,
        ListeningLogEntity::class,
        ImportedListeningDayEntity::class,
        ImportedListeningItemEntity::class,
        ListeningImportEntity::class
    ],
    // Version 9 changes no table: its migration (Migration8To9, Android) drops the Plex rows stored before albums were read.
    // Version 10 adds book_merges (the user's corrections of merged sources).
    // Version 11 adds the listening log and the imported listening history (statistics).
    // Version 12 adds progress.mirrored (a row copied from another copy of a merged book).
    // Version 13 adds pending_progress.mirrorOf (a queued position that repeats another copy's listening).
    version = 13,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13),
    ],
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
    abstract fun pendingProgressDao(): PendingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun bookMergeDao(): BookMergeDao
    abstract fun listeningDao(): ListeningDao

    companion object {
        const val DATABASE_NAME = "chapter_player.db"
    }
}

/** Room generates the actual for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object ChapterPlayerDatabaseConstructor : RoomDatabaseConstructor<ChapterPlayerDatabase> {
    override fun initialize(): ChapterPlayerDatabase
}
