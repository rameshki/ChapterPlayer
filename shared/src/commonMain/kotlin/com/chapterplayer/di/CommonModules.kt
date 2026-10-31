package com.chapterplayer.di

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.RoomDownloadStates
import com.chapterplayer.data.auth.AbsSignInPort
import com.chapterplayer.data.bookmarks.BookmarkSyncer
import com.chapterplayer.data.bookmarks.HistoryRecorder
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.merge.AppSourceMerging
import com.chapterplayer.data.merge.MergedProgressPeers
import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackProgressSaver
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.progress.ProgressMirrorPusher
import com.chapterplayer.data.progress.ProgressSyncer
import com.chapterplayer.data.progress.ServerProgressRefresher
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.BookRepositoryImpl
import com.chapterplayer.data.stats.BookOwner
import com.chapterplayer.data.stats.BookOwners
import com.chapterplayer.data.stats.ListeningImport
import com.chapterplayer.data.stats.ListeningLog
import com.chapterplayer.data.stats.ListeningRecorder
import com.chapterplayer.data.stats.ListeningStatsImporter
import com.chapterplayer.data.stats.RoomListeningLog
import com.chapterplayer.domain.browse.BrowseQueryStore
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.ui.details.BookDetailsViewModel
import com.chapterplayer.ui.downloads.DownloadsViewModel
import com.chapterplayer.ui.home.HomeViewModel
import com.chapterplayer.ui.library.GroupDetailViewModel
import com.chapterplayer.ui.library.LibrariesViewModel
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.library.LibraryViewModel
import com.chapterplayer.ui.player.PlayerMenuViewModel
import com.chapterplayer.ui.player.PlayerViewModel
import com.chapterplayer.ui.search.SearchViewModel
import com.chapterplayer.ui.settings.SettingsViewModel
import com.chapterplayer.ui.stats.StatsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
import com.chapterplayer.data.sync.ForegroundSync
import com.chapterplayer.data.sync.SyncAllServers
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
 * the blocking I/O dispatcher (BlockingIoQualifier), the DayZone and the ListeningClock, and the player and download
 * parts: PlaybackController, LocalBookFiles, DownloadController, DownloadStorage, DownloadOverview, NotificationPermission
 * and PlatformFeatures (Android: the real ones; iOS: the stand-ins of notYetOnIosModule until Stage 5 steps 4 and 5).
 * Android's modules (androidMain KoinModules.kt) include these; iOS starts them with initKoin (iosMain KoinIos.kt).
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
    // The play request names this platform's player (AbsApiService's default, platformAbsPlayer).
    single { AbsApiService(get()) }
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
        PlexLibrarySource(get()) { refused -> blankRefusedPlexToken(servers, refused) }
    } bind LibrarySource::class
}

/** Blanks [refused]'s Plex token if it is still the one the server refused, so Settings says "Sign in again". */
internal suspend fun blankRefusedPlexToken(servers: ServerRepository, refused: com.chapterplayer.data.model.ServerConfig) {
    val current = servers.getById(refused.id)
    if (current != null && current.token.isNotBlank() && current.token == refused.token) servers.save(current.copy(token = ""))
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
    // iOS only (no background sync there yet): every server syncs at start, on becoming active and on a network change.
    single<SyncAllServers> { get<LibrarySyncCoordinator>() }
    single { ForegroundSync(get(), get(), get(AppScopeQualifier)) }
}

/** The book repository, the other copies of merged books, and the download states the lists show. */
val commonRepositoryModule = module {
    single<BookRepository> { BookRepositoryImpl(
            get(), get(), get(), get(), get(), getAll<LibrarySource>(), pendingDao = get(), bookmarkDao = get(), historyDao = get(),
            inTransaction = { block ->
                get<ChapterPlayerDatabase>().useWriterConnection { it.immediateTransaction { block() } }
            },
            onQueued = { get<PendingProgressFlusher>().kick() },
            // A Plex start that cannot reach the cached address re-probes and retries once on the new one.
            onConnectionFailure = { server, error -> get<PlexConnectionManager>().afterFailure(server, error, force = false, notify = true).retryWith },
            // A save of one copy of a merged book moves the other copies' local rows (Settings switch, on by default).
            mirrorPeers = get(),
            // Looked up when a mirror is written, not when this is built (the player is in the platform's module).
            loadedBookId = { get<PlaybackController>().state.value.bookId }
        ) }
    // The other copies of a merged book, grouped like the lists (the user's corrections included).
    single<ProgressMirrorPeers> {
        MergedProgressPeers(
            get<BookDao>().observeAll().map { rows -> rows.map { it.toDomain(emptyList()) } },
            get<BookMergeRepository>().overrides,
            get<AppSettings>().mirrorProgress,
            get(AppScopeQualifier),
        )
    }
    single<DownloadStates> { RoomDownloadStates(get()) }
}

/** The filters shared by Home, Library and Search, and the merging of books several servers have. */
val commonBrowseModule = module {
    // In memory only. A deleted server drops out of the server choice.
    single {
        BrowseQueryStore().also { store ->
            val serverIds = get<ServerRepository>().observeAll()
                .map { list -> list.mapTo(mutableSetOf()) { it.id } }
                .catch { e -> if (e is CancellationException) throw e }
            store.followServers(serverIds, get(AppScopeQualifier))
        }
    }
    // Joins a book several servers have into one entry, for every list (Library, Home, Search, details, car, widgets).
    single<SourceMerging> { AppSourceMerging(get(), get(), get(), get(), get()) }
}

/**
 * What follows the player whichever screen is open (progress saves, server sync and its queue, mirrors, bookmarks,
 * history, listening time) and the one way to start a book. The platform gives the PlaybackController (iOS: a stand-in
 * until step 4), the LocalBookFiles, the DayZone and the ListeningClock.
 */
val commonPlaybackModule = module {
    // Saves listening progress whichever screen is open; the application starts it.
    single { PlaybackProgressSaver(get(), get(), get(AppScopeQualifier)) }
    // Tells the server where listening is, and queues what it did not get; the flusher delivers the queue.
    single { ProgressSyncer(get(), get(), get(AppScopeQualifier), onQueued = { get<PendingProgressFlusher>().kick() }) }
    single {
        PendingProgressFlusher(get(), get(), get(AppScopeQualifier), peers = get(), loadedBookId = { get<PlaybackController>().state.value.bookId })
    }
    // The other copies of a merged book: their servers hear the mapped position through the queue (throttled).
    single {
        ProgressMirrorPusher(get(), get(), get(), statusStore = get(), scope = get(AppScopeQualifier), onQueued = { get<PendingProgressFlusher>().kick() })
    }
    single { ServerProgressRefresher(get(), get(), get(), get(AppScopeQualifier)) }
    single { BookmarkSyncer(get(), get(), get(), get(AppScopeQualifier)) }
    single { HistoryRecorder(get(), get(), get(AppScopeQualifier)) }
    // Listening time per day and book for the statistics page, from every source, online or not.
    single<ListeningLog> {
        RoomListeningLog(get(), inTransaction = { block -> get<ChapterPlayerDatabase>().useWriterConnection { it.immediateTransaction { block() } } })
    }
    single<BookOwners> {
        val bookDao = get<BookDao>()
        BookOwners { id -> bookDao.getById(id)?.let { book -> BookOwner(book.serverId, ServerSource.entries.firstOrNull { it.name == book.source } ?: ServerSource.ABS) } }
    }
    single { ListeningRecorder(get(), get(), get(), get(AppScopeQualifier), get(), get()) }
    // Audiobookshelf's own listening history, imported once per server (and again from the stats page).
    single<ListeningImport> { ListeningStatsImporter(get(), getAll<LibrarySource>(), get(), get(), get(AppScopeQualifier), get()) }
    // Starts a book (position, saved speed, play) for details, Home and the player.
    singleOf(::PlaybackStarter)
    // A tapped notification asks the navigation host to open the player once.
    single { OpenPlayerRequest() }
}

/** Every screen's view model. */
val commonViewModelModule = module {
    // The Servers page: sync state, Sync now and Test connection; a sync started there runs in the app scope.
    viewModel { ServersViewModel(get(), get(), get(AppScopeQualifier)) }
    viewModel { ServerSetupViewModel(get(), get(), get(), get(), plexConnections = get(), plexProbe = get(), work = get(IoDispatcherQualifier)) }
    viewModelOf(::OfflineStateViewModel)
    viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), get(), merging = get()) }
    viewModelOf(::LibrariesViewModel)
    // Built with parametersOf(kind, groupKey) by the group detail screen.
    viewModel { params ->
        GroupDetailViewModel(
            params.get<LibraryView>(), params.get<String>(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), merging = get()
        )
    }
    // Built with parametersOf(bookId) by the book details screen.
    viewModel { params -> BookDetailsViewModel(
            params.get<String>(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), get(), get(), get(),
            merging = get(), merges = get(), servers = get(), features = get()
        ) }
    viewModel { PlayerViewModel(get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { PlayerMenuViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier), merging = get(), merges = get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(AppScopeQualifier), get()) }
    // The statistics page; its figures are worked out off the main thread.
    viewModel { StatsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), merging = get()) }
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier)) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(IoDispatcherQualifier), merging = get()) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(IoDispatcherQualifier), merging = get()) }
}

/** Every common module; a platform adds its own (Android: `sharedModules` includes these; iOS: `initKoin`). */
val commonModules: List<Module> =
    listOf(
        commonDaoModule, commonNetworkModule, commonStoreModule, commonSourceModule, commonServerModule, commonRepositoryModule,
        commonBrowseModule, commonPlaybackModule, commonViewModelModule,
    )
