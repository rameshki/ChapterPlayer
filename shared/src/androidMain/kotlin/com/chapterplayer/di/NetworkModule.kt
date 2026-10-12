package com.chapterplayer.di

import com.chapterplayer.data.remote.createHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * One HTTP client for every server. ABS and Plex Media Server live at a different base URL
     * per server, so each API class takes the base URL per call; plex.tv has a fixed one.
     * Debug builds log requests and bodies with auth headers hidden.
     */
    @Provides
    @Singleton
    fun provideHttpClient(@Named("debuggable") isDebuggable: Boolean): HttpClient =
        createHttpClient(isDebuggable)
}
