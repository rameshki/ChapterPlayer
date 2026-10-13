package com.chapterplayer.di

import androidx.room.Room
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.createHttpClient
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.AndroidPlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.BookRepositoryImpl
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.repository.ServerRepositoryImpl
import com.chapterplayer.ui.library.LibraryViewModel
import com.chapterplayer.ui.settings.ServerSetupViewModel
import com.chapterplayer.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** The debug flag. The shared module has no BuildConfig, so the app module provides it. */
val DebuggableQualifier = named("debuggable")

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            ChapterPlayerDatabase::class.java,
            ChapterPlayerDatabase.DATABASE_NAME
        ).build()
    }
    single { get<ChapterPlayerDatabase>().bookDao() }
    single { get<ChapterPlayerDatabase>().progressDao() }
    single { get<ChapterPlayerDatabase>().serverConfigDao() }
}

val networkModule = module {
    // One HTTP client for every server. Debug builds log requests with auth headers hidden.
    single { createHttpClient(get<Boolean>(DebuggableQualifier)) }
    singleOf(::AbsApiService)
    singleOf(::PlexApiService)
    singleOf(::PlexTvApiService)
    singleOf(::AndroidPlexClientIdentifier) { bind<PlexClientIdentifier>() }
}

val repositoryModule = module {
    singleOf(::BookRepositoryImpl) { bind<BookRepository>() }
    singleOf(::ServerRepositoryImpl) { bind<ServerRepository>() }
}

val viewModelModule = module {
    viewModelOf(::LibraryViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ServerSetupViewModel)
}

/** Everything the shared module declares. The app adds what only it knows. */
val sharedModules = listOf(databaseModule, networkModule, repositoryModule, viewModelModule)
