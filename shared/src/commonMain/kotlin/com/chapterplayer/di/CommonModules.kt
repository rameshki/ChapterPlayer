package com.chapterplayer.di

import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.images.PlexImageServers
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.merge.BookMergeRepository
import com.chapterplayer.data.merge.RoomBookMergeRepository
import com.chapterplayer.data.remote.abs.AbsApiService
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.remote.abs.AbsSessionManager
import com.chapterplayer.data.remote.abs.AbsSignIn
import com.chapterplayer.data.remote.createHttpClient
import com.chapterplayer.data.remote.createProbeHttpClient
import com.chapterplayer.data.remote.plex.KtorPlexConnectionProbe
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexTvApiService
import com.chapterplayer.data.remote.plex.PlexTvCandidateLookup
import com.chapterplayer.data.repository.LibraryCatalog
import com.chapterplayer.data.repository.LibraryCatalogImpl
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.repository.ServerRepositoryImpl
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.DataStoreAppSettings
import com.chapterplayer.data.source.AbsLibrarySource
import com.chapterplayer.data.source.LibrarySource
import com.chapterplayer.data.source.PlexLibrarySource
import com.chapterplayer.data.sync.DataStoreServerStatusStore
import com.chapterplayer.data.sync.LibrarySync
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.data.sync.NoPlexSectionLengths
import com.chapterplayer.data.sync.PlexCandidateLookup
import com.chapterplayer.data.sync.PlexConnectionManager
import com.chapterplayer.data.sync.PlexConnectionProbe
import com.chapterplayer.data.sync.PlexLengthFiller
import com.chapterplayer.data.sync.PlexSectionLengths
import com.chapterplayer.data.sync.ServerStatusStore
import com.chapterplayer.ui.offline.OfflineStateViewModel
import com.chapterplayer.ui.settings.ServerSetupViewModel
import com.chapterplayer.ui.settings.ServersViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/*
 * The bindings every platform shares. Each platform adds a module with what only it can make: the database
 * (ChapterPlayerDatabase), the DataStores named by SettingsStoreQualifier and ServerStatusStoreQualifier, the
 * CredentialStore, the PlexClientIdentifier, the ConnectivityMonitor, the AppInfo, the debug flag (DebuggableQualifier),
 * the blocking I/O dispatcher (BlockingIoQualifier) and a BookRepository (Android: the real one; iOS: a stand-in until
 * the repositories are shared). Android's modules (androidMain KoinModules.kt) include these; iOS starts them with
 * initKoin (iosMain KoinIos.kt).
 */

/** The debug flag. The shared module has no BuildConfig, so the app (Android) or the platform module (iOS) provides it. */
val DebuggableQualifier = named("debuggable")

val SettingsStoreQualifier = named("settings")
val CredentialsStoreQualifier = named("credentials")
val ServerStatusStoreQualifier = named("serverStatus")

/** The HTTP client that probes Plex connections: 5 s timeouts, no redirects. */
val ProbeClientQualifier = named("probeClient")

/** The app-long coroutine scope, and the dispatcher the browse view models do their list work on. */
val AppScopeQualifier = named("appScope")
val IoDispatcherQualifier = named("io")

/** The dispatcher for blocking I/O (Dispatchers.IO, which common code cannot name): Plex probes and plex.tv reads. */
val BlockingIoQualifier = named("blockingIo")

/** The HTTP client for audio downloads: no request timeout, no redirects followed (see installDownloadDefaults). */
val DownloadClientQualifier = named("downloadClient")

/** The DAOs, from the platform's database. */
val commonDaoModule = module {
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
    single { get<ChapterPlayerDatabase>().bookMergeDao() }
    single { get<ChapterPlayerDatabase>().listeningDao() }
}

/** The HTTP clients and the server APIs. */
val commonNetworkModule = module {
    // One HTTP client for every server. Debug builds log requests with auth headers hidden.
    single { createHttpClient(get<Boolean>(DebuggableQualifier)) }
    singleOf(::AbsApiService)
    singleOf(::AbsSignIn) { bind<AbsSignInPort>() }
    // The playback reports name this installation to the server.
    single { PlexApiService(get(), get<PlexClientIdentifier>()) }
    singleOf(::PlexTvApiService)
    // Plex connection choice: probes each address plex.tv lists, and asks plex.tv for the list of a server saved earlier.
    single(ProbeClientQualifier) { createProbeHttpClient(get<Boolean>(DebuggableQualifier)) }
    single<PlexConnectionProbe> { KtorPlexConnectionProbe(get(ProbeClientQualifier)) }
    single<PlexCandidateLookup> { PlexTvCandidateLookup(get(), get()) }
}

/** The stores on the platform's DataStore files (the credential store is the platform's own). */
val commonStoreModule = module {
    single<AppSettings> { DataStoreAppSettings(get(SettingsStoreQualifier)) }
    // Each server's connections (Plex) and last sync; no secrets.
    single<ServerStatusStore> { DataStoreServerStatusStore(get(ServerStatusStoreQualifier)) }
}

/** How each kind of server is read. */
val commonSourceModule = module {
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

/** The server list, sign-in, connection choice and sync, with the app scope and the dispatcher they run on. */
val commonServerModule = module {
    single<ServerRepository> { ServerRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get(), statusStore = get(), bookMergeDao = get()) }
    singleOf(::LibraryCatalogImpl) { bind<LibraryCatalog>() }
    // The user's "Not the same book" and "Merge with" choices for books on several servers.
    single<BookMergeRepository> { RoomBookMergeRepository(get()) }
    single<AbsAuthorizer> { AbsSessionManager(get(), get()) }
    // Lives as long as the app: syncs servers whichever screen is open.
    single<CoroutineScope>(AppScopeQualifier) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // Where the browse view models do their list work; tests pass their own.
    single<CoroutineDispatcher>(IoDispatcherQualifier) { Dispatchers.Default }
    // Chooses each Plex server's address; the application starts its network watch.
    single { PlexConnectionManager(get(), get(), get(), get(), io = get(BlockingIoQualifier)) }
    // A switched Plex address syncs that server again, so stored cover addresses follow the address in use.
    single {
        LibrarySyncCoordinator(get(), get(), get(AppScopeQualifier), connections = get(), statusStore = get(), lengthFiller = get()).also { sync ->
            get<PlexConnectionManager>().onSwitched = { id -> sync.requestSync(id) }
        }
    }
    // After a Plex sync, reads the album lengths the album list leaves out (background, one pass per server).
    single {
        PlexLengthFiller(get(), getAll<LibrarySource>().filterIsInstance<PlexSectionLengths>().firstOrNull() ?: NoPlexSectionLengths, get(AppScopeQualifier))
    }
    // The Plex servers the cover loader adds the token header for.
    single { PlexImageServers(get(), get(AppScopeQualifier)) }
    single<LibrarySync> { get<LibrarySyncCoordinator>() }
}

/** The view models of the screens that need no platform-only binding: the Servers pages and the offline state. */
val commonViewModelModule = module {
    // The Servers page: sync state, Sync now and Test connection; a sync started there runs in the app scope.
    viewModel { ServersViewModel(get(), get(), get(AppScopeQualifier)) }
    viewModel { ServerSetupViewModel(get(), get(), get(), get(), plexConnections = get(), plexProbe = get(), work = get(IoDispatcherQualifier)) }
    viewModelOf(::OfflineStateViewModel)
}

/** Every common module; a platform adds its own (Android: `sharedModules` includes these; iOS: `initKoin`). */
val commonModules: List<Module> =
    listOf(commonDaoModule, commonNetworkModule, commonStoreModule, commonSourceModule, commonServerModule, commonViewModelModule)
