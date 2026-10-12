package com.chapterplayer.di

import com.chapterplayer.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * Values only the app module knows. The shared module has no BuildConfig, so the
 * debug flag is provided here and injected where it is needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Named("debuggable")
    fun provideDebuggable(): Boolean = BuildConfig.DEBUG
}
