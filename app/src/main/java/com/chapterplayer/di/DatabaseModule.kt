package com.chapterplayer.di

import android.content.Context
import androidx.room.Room
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.local.dao.ProgressDao
import com.chapterplayer.data.local.dao.ServerConfigDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChapterPlayerDatabase =
        Room.databaseBuilder(
            context,
            ChapterPlayerDatabase::class.java,
            ChapterPlayerDatabase.DATABASE_NAME
        ).build()

    @Provides
    fun provideBookDao(database: ChapterPlayerDatabase): BookDao = database.bookDao()

    @Provides
    fun provideProgressDao(database: ChapterPlayerDatabase): ProgressDao = database.progressDao()

    @Provides
    fun provideServerConfigDao(database: ChapterPlayerDatabase): ServerConfigDao =
        database.serverConfigDao()
}
