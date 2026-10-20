package com.chapterplayer.di

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.NoDownloadStates
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.connectivity.AndroidConnectivityMonitor
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.createPreferencesDataStore
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.playback.AndroidPlaybackController
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackProgressSaver
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.progress.ProgressSyncer
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.remote.abs.AbsSignIn
import com.chapterplayer.data.remote.createHttpClient
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.AndroidPlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.BookRepositoryImpl
import com.chapterplayer.data.repository.LibraryCatalog
import com.chapterplayer.data.repository.LibraryCatalogImpl
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.repository.ServerRepositoryImpl
import com.chapterplayer.data.security.AesGcmTokenCipher
import com.chapterplayer.data.security.AndroidKeystoreKey
import com.chapterplayer.data.security.CredentialStore
import com.chapterplayer.data.security.DataStoreCredentialStore
import com.chapterplayer.data.security.TokenCipher
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.DataStoreAppSettings
import com.chapterplayer.data.source.AbsLibrarySource
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.data.source.PlexLibrarySource
import com.chapterplayer.data.sync.LibrarySync
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.ui.home.HomeViewModel
import com.chapterplayer.ui.details.BookDetailsViewModel
import com.chapterplayer.ui.library.GroupDetailViewModel
import com.chapterplayer.ui.library.LibrariesViewModel
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.library.LibraryViewModel
import com.chapterplayer.ui.player.PlayerViewModel
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.ui.search.SearchViewModel
import com.chapterplayer.ui.offline.OfflineStateViewModel
import com.chapterplayer.ui.settings.ServerSetupViewModel
import com.chapterplayer.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** The debug flag. The shared module has no BuildConfig, so the app module provides it. */
val DebuggableQualifier = named("debuggable")

val SettingsStoreQualifier = named("settings")
val CredentialsStoreQualifier = named("credentials")

/** The app-long coroutine scope, and the dispatcher the browse view models do their list work on. */
val AppScopeQualifier = named("appScope")
val IoDispatcherQualifier = named("io")

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
    single { get<ChapterPlayerDatabase>().excludedLibraryDao() }
    single { get<ChapterPlayerDatabase>().libraryDao() }
    single { get<ChapterPlayerDatabase>().collectionDao() }
    single { get<ChapterPlayerDatabase>().pendingProgressDao() }
}

val networkModule = module {
    // One HTTP client for every server. Debug builds log requests with auth headers hidden.
    single { createHttpClient(get<Boolean>(DebuggableQualifier)) }
    singleOf(::AbsApiService)
    singleOf(::AbsSignIn) { bind<AbsSignInPort>() }
    singleOf(::PlexApiService)
    singleOf(::PlexTvApiService)
    singleOf(::AndroidPlexClientIdentifier) { bind<PlexClientIdentifier>() }
}

val securityModule = module {
    single(SettingsStoreQualifier) { createPreferencesDataStore(androidContext(), "settings") }
    single(CredentialsStoreQualifier) { createPreferencesDataStore(androidContext(), "credentials") }
    single<TokenCipher> { AesGcmTokenCipher { AndroidKeystoreKey.getOrCreate() } }
    single<CredentialStore> { DataStoreCredentialStore(get(CredentialsStoreQualifier), get()) }
    single<AppSettings> { DataStoreAppSettings(get(SettingsStoreQualifier)) }
}

val sourceModule = module {
    singleOf(::AbsLibrarySource) { bind<LibrarySource>() }
    singleOf(::PlexLibrarySource) { bind<LibrarySource>() }
}

val repositoryModule = module {
    single<BookRepository> { BookRepositoryImpl(
            get(), get(), get(), get(), get(), getAll<LibrarySource>(), pendingDao = get(),
            inTransaction = { block ->
                get<ChapterPlayerDatabase>().useWriterConnection { it.immediateTransaction { block() } }
            }
        ) }
    singleOf(::ServerRepositoryImpl) { bind<ServerRepository>() }
    singleOf(::LibraryCatalogImpl) { bind<LibraryCatalog>() }
    // Stage 2 has no downloads; Stage 3 replaces this binding.
    single<DownloadStates> { NoDownloadStates() }
    single<AbsAuthorizer> { AbsSessionManager(get(), get()) }
    // Lives as long as the app: syncs servers whichever screen is open.
    single<CoroutineScope>(AppScopeQualifier) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { LibrarySyncCoordinator(get(), get(), get(AppScopeQualifier)) }
    single<LibrarySync> { get<LibrarySyncCoordinator>() }
}

val browseModule = module {
    // The filters shared by Home, Library and Search; in memory only.
    single { BrowseQueryStore() }
    // Where the browse view models do their list work; tests pass their own.
    single<CoroutineDispatcher>(IoDispatcherQualifier) { Dispatchers.Default }
}

val playbackModule = module {
    // One controller for the app; the service itself finds its parts through Koin too.
    single<PlaybackController> { AndroidPlaybackController(androidContext(), get(), get(AppScopeQualifier)) }
    // Saves listening progress whichever screen is open; the application starts it.
    single { PlaybackProgressSaver(get(), get(), get(AppScopeQualifier)) }
    // Tells the server where listening is, and queues what it did not get; the flusher delivers the queue.
    single { ProgressSyncer(get(), get(), get(AppScopeQualifier), onQueued = { get<PendingProgressFlusher>().kick() }) }
    single { PendingProgressFlusher(get(), get(), get(AppScopeQualifier)) }
    // Starts a book (position, saved speed, play) for details, Home and the player.
    singleOf(::PlaybackStarter)
    // A tapped notification asks the navigation host to open the player once.
    single { OpenPlayerRequest() }
}

val deviceModule = module {
    single<ConnectivityMonitor> { AndroidConnectivityMonitor(androidContext()) }
}

val viewModelModule = module {
    viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), get()) }
    viewModelOf(::LibrariesViewModel)
    // Built with parametersOf(kind, groupKey) by the group detail screen.
    viewModel { params ->
        GroupDetailViewModel(
            params.get<LibraryView>(), params.get<String>(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier)
        )
    }
    // Built with parametersOf(bookId) by the book details screen.
    viewModel { params -> BookDetailsViewModel(params.get<String>(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { PlayerViewModel(get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ServerSetupViewModel)
    viewModelOf(::OfflineStateViewModel)
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
}

/** Everything the shared module declares. The app adds what only it knows. */
val sharedModules = listOf(databaseModule, networkModule, securityModule, sourceModule, repositoryModule, deviceModule, playbackModule, browseModule, viewModelModule)
