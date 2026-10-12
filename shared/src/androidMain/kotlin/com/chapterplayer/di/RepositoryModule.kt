package com.chapterplayer.di

import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.BookRepositoryImpl
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.repository.ServerRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository
}
