package com.chapterplayer.di

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.RoomDownloadStates
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.connectivity.AndroidConnectivityMonitor
import com.chapterplayer.data.permissions.AndroidNotificationPermission
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.data.downloads.SettingsDownloadLocation
import com.chapterplayer.data.downloads.BookDownloader
import com.chapterplayer.data.downloads.DefaultDownloadController
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadJobs
import com.chapterplayer.data.downloads.DownloadLocation
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.AndroidLocalDisk
import com.chapterplayer.data.downloads.DownloadLengthFiller
import com.chapterplayer.data.downloads.DownloadRemovalListener
import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.downloads.RoomLocalBookFiles
import com.chapterplayer.data.downloads.LiveDownloadProgress
import com.chapterplayer.data.downloads.RoomDownloadOverview
import com.chapterplayer.data.downloads.DownloadQueueRestorer
import com.chapterplayer.data.downloads.DownloadScheduler
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.data.downloads.FileDownloader
import com.chapterplayer.data.downloads.WorkManagerDownloadScheduler
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.createPreferencesDataStore
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.playback.AndroidPlaybackController
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackProgressSaver
import com.chapterplayer.data.bookmarks.BookmarkSyncer
import com.chapterplayer.data.bookmarks.HistoryRecorder
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.progress.ProgressSyncer
import com.chapterplayer.data.progress.ServerProgressRefresher
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.remote.abs.AbsSignIn
import com.chapterplayer.data.images.PlexImageServers
import com.chapterplayer.data.local.Migration8To9
import com.chapterplayer.data.remote.createDownloadHttpClient
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
import com.chapterplayer.ui.player.PlayerMenuViewModel
import com.chapterplayer.ui.player.PlayerViewModel
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.ui.search.SearchViewModel
import com.chapterplayer.ui.offline.OfflineStateViewModel
import com.chapterplayer.ui.downloads.DownloadsViewModel
import com.chapterplayer.ui.settings.ServerSetupViewModel
import com.chapterplayer.ui.settings.SettingsViewModel
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetRefresher
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.data.widgets.WidgetHost
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.ui.widgets.GlanceWidgetHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/** The debug flag. The shared module has no BuildConfig, so the app module provides it. */
val DebuggableQualifier = named("debuggable")

val SettingsStoreQualifier = named("settings")
val CredentialsStoreQualifier = named("credentials")

/** The app-long coroutine scope, and the dispatcher the browse view models do their list work on. */
val AppScopeQualifier = named("appScope")
val IoDispatcherQualifier = named("io")

/** The HTTP client for audio downloads: no request timeout, no redirects followed (see installDownloadDefaults). */
val DownloadClientQualifier = named("downloadClient")

val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            ChapterPlayerDatabase::class.java,
            ChapterPlayerDatabase.DATABASE_NAME
        ).addMigrations(Migration8To9).build()
    }
    single { get<ChapterPlayerDatabase>().bookDao() }
    single { get<ChapterPlayerDatabase>().progressDao() }
    single { get<ChapterPlayerDatabase>().serverConfigDao() }
    single { get<ChapterPlayerDatabase>().excludedLibraryDao() }
    single { get<ChapterPlayerDatabase>().libraryDao() }
    single { get<ChapterPlayerDatabase>().collectionDao() }
    single { get<ChapterPlayerDatabase>().pendingProgressDao() }
    single { get<ChapterPlayerDatabase>().bookmarkDao() }
    single { get<ChapterPlayerDatabase>().historyDao() }
    single { get<ChapterPlayerDatabase>().downloadDao() }
}

val networkModule = module {
    // One HTTP client for every server. Debug builds log requests with auth headers hidden.
    single { createHttpClient(get<Boolean>(DebuggableQualifier)) }
    singleOf(::AbsApiService)
    singleOf(::AbsSignIn) { bind<AbsSignInPort>() }
    // The playback reports name this installation to the server.
    single { PlexApiService(get(), get<PlexClientIdentifier>()) }
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
    // A refused Plex token cannot be refreshed: it is blanked (if it is still the one that was refused), so Settings says "Sign in again".
    single {
        val servers = get<ServerRepository>()
        PlexLibrarySource(get()) { refused ->
            val current = servers.getById(refused.id)
            if (current != null && current.token.isNotBlank() && current.token == refused.token) servers.save(current.copy(token = ""))
        }
    } bind LibrarySource::class
}

val repositoryModule = module {
    single<BookRepository> { BookRepositoryImpl(
            get(), get(), get(), get(), get(), getAll<LibrarySource>(), pendingDao = get(), bookmarkDao = get(), historyDao = get(),
            inTransaction = { block ->
                get<ChapterPlayerDatabase>().useWriterConnection { it.immediateTransaction { block() } }
            },
            onQueued = { get<PendingProgressFlusher>().kick() }
        ) }
    singleOf(::ServerRepositoryImpl) { bind<ServerRepository>() }
    singleOf(::LibraryCatalogImpl) { bind<LibraryCatalog>() }
    single<DownloadStates> { RoomDownloadStates(get()) }
    // A downloaded book's files, for playing it from the phone (playback and the downloads share it).
    single<LocalBookFiles> { RoomLocalBookFiles(get(), AndroidLocalDisk, Dispatchers.IO, nowMs = System::currentTimeMillis) }
    single<AbsAuthorizer> { AbsSessionManager(get(), get()) }
    // Lives as long as the app: syncs servers whichever screen is open.
    single<CoroutineScope>(AppScopeQualifier) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { LibrarySyncCoordinator(get(), get(), get(AppScopeQualifier)) }
    // The Plex servers the cover loader adds the token header for.
    single { PlexImageServers(get(), get(AppScopeQualifier)) }
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
    single<PlaybackController> { AndroidPlaybackController(androidContext(), get(), get(AppScopeQualifier), get()) }
    // Saves listening progress whichever screen is open; the application starts it.
    single { PlaybackProgressSaver(get(), get(), get(AppScopeQualifier)) }
    // Tells the server where listening is, and queues what it did not get; the flusher delivers the queue.
    single { ProgressSyncer(get(), get(), get(AppScopeQualifier), onQueued = { get<PendingProgressFlusher>().kick() }) }
    single { PendingProgressFlusher(get(), get(), get(AppScopeQualifier)) }
    single { ServerProgressRefresher(get(), get(), get(), get(AppScopeQualifier)) }
    single { BookmarkSyncer(get(), get(), get(), get(AppScopeQualifier)) }
    single { HistoryRecorder(get(), get(), get(AppScopeQualifier)) }
    // Starts a book (position, saved speed, play) for details, Home and the player.
    singleOf(::PlaybackStarter)
    // A tapped notification asks the navigation host to open the player once.
    single { OpenPlayerRequest() }
}

val downloadModule = module {
    single(DownloadClientQualifier) { createDownloadHttpClient(get<Boolean>(DebuggableQualifier)) }
    single { SettingsDownloadLocation(androidContext(), get()) } binds arrayOf(DownloadLocation::class, DownloadStorage::class)
    single { FileDownloader(get(DownloadClientQualifier), get()) }
    // One per app: the worker runs it, and its progress flow feeds the notification (and later the UI).
    single {
        BookDownloader(get(), get(), get(), get(), get(), get(), get(), limitBytes = { get<AppSettings>().downloadStorageLimitBytes.first() })
    } bind DownloadJobs::class
    single<LiveDownloadProgress> { get<BookDownloader>() }
    single<DownloadOverview> { RoomDownloadOverview(get(), get()) }
    single<DownloadScheduler> { WorkManagerDownloadScheduler(androidContext()) }
    // The player is looked up when a download is removed, not when this is built.
    single<DownloadRemovalListener> { DownloadRemovalListener { bookId -> get<PlaybackController>().releaseLocalFiles(bookId) } }
    single { DefaultDownloadController(get(), get(), get(), get(), get(), get(), get()) }
    single<DownloadController> { get<DefaultDownloadController>() }
    // The application starts it: whenever the phone comes online, fills in the file lengths older downloads lack.
    single { DownloadLengthFiller(get(), get(), get(), get(), get(), get(), get(AppScopeQualifier)) }
    // The application starts it: reschedules downloads after a reboot or process death.
    single { DownloadQueueRestorer(get(), get(AppScopeQualifier)) }
}

val deviceModule = module {
    single<ConnectivityMonitor> { AndroidConnectivityMonitor(androidContext()) }
    // The main activity lends it its permission launcher; the details screen asks before the first download.
    single { AndroidNotificationPermission(androidContext()) } bind NotificationPermission::class
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
    viewModel { params -> BookDetailsViewModel(params.get<String>(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), get(), get(), get()) }
    viewModel { PlayerViewModel(get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { PlayerMenuViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(AppScopeQualifier), get()) }
    viewModelOf(::ServerSetupViewModel)
    viewModelOf(::OfflineStateViewModel)
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
}

val widgetModule = module {
    // What the home screen widgets show, and the Now Playing buttons (they act on the loaded book only).
    single { WidgetStates(get(), get(), get(), get(), get()) }
    single { WidgetActions(get()) }
    // The application starts it: redraws the widgets when what they show changes (not on every position tick).
    single<WidgetHost> { GlanceWidgetHost(androidContext()) }
    single { WidgetRefresher(get(), get(), get(AppScopeQualifier)) }
    // The Continue widget's Resume: the main activity hands it the book (the one load path, offline rules apply).
    // It runs in the app scope, so a rotation or leaving the activity does not cut a start short.
    single { WidgetResumer(get(), get(), get(), get(), get(AppScopeQualifier)) }
}

/** Everything the shared module declares. The app adds what only it knows. */
val sharedModules = listOf(databaseModule, networkModule, securityModule, sourceModule, repositoryModule, deviceModule, playbackModule, downloadModule, browseModule, viewModelModule, widgetModule)
