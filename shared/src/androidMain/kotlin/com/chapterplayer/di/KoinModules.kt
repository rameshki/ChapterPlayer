package com.chapterplayer.di

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.RoomDownloadStates
import com.chapterplayer.data.connectivity.AndroidConnectivityMonitor
import com.chapterplayer.data.permissions.AndroidNotificationPermission
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.data.downloads.SettingsDownloadLocation
import com.chapterplayer.data.downloads.AbsDownloadSource
import com.chapterplayer.data.downloads.BookDownloader
import com.chapterplayer.data.downloads.DownloadSource
import com.chapterplayer.data.downloads.PlexDownloadSource
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
import com.chapterplayer.data.stats.AndroidListeningClock
import com.chapterplayer.data.stats.BookOwner
import com.chapterplayer.data.stats.BookOwners
import com.chapterplayer.data.stats.ListeningClock
import com.chapterplayer.data.stats.ListeningImport
import com.chapterplayer.data.stats.ListeningLog
import com.chapterplayer.data.stats.ListeningStatsImporter
import com.chapterplayer.data.stats.ListeningRecorder
import com.chapterplayer.data.stats.RoomListeningLog
import com.chapterplayer.data.stats.SystemDayZone
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.stats.DayZone
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.progress.ProgressSyncer
import com.chapterplayer.data.progress.ProgressMirrorPusher
import com.chapterplayer.data.progress.ServerProgressRefresher
import com.chapterplayer.data.playback.PlaybackStarter
import com.chapterplayer.data.local.Migration8To9
import com.chapterplayer.data.remote.createDownloadHttpClient
import com.chapterplayer.data.sync.PlexConnectionManager
import com.chapterplayer.data.remote.plex.AndroidPlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.merge.BookMergeRepository
import com.chapterplayer.data.merge.AppSourceMerging
import com.chapterplayer.data.merge.SourceMerging
import com.chapterplayer.data.merge.MergedProgressPeers
import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.BookRepositoryImpl
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.security.AesGcmTokenCipher
import com.chapterplayer.data.security.AndroidKeystoreKey
import com.chapterplayer.data.security.CredentialStore
import com.chapterplayer.data.security.DataStoreCredentialStore
import com.chapterplayer.data.security.TokenCipher
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.source.LibrarySource
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
import com.chapterplayer.ui.stats.StatsViewModel
import com.chapterplayer.ui.downloads.DownloadsViewModel
import com.chapterplayer.ui.settings.SettingsViewModel
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetRefresher
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.data.widgets.WidgetHost
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.ui.widgets.GlanceWidgetHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/*
 * Android's modules. Each includes the common module of its area (di/CommonModules.kt, shared with iOS, where the
 * qualifiers are declared too) and adds what only Android has.
 */

val databaseModule = module {
    includes(commonDaoModule)
    single {
        Room.databaseBuilder(
            androidContext(),
            ChapterPlayerDatabase::class.java,
            ChapterPlayerDatabase.DATABASE_NAME
        ).addMigrations(Migration8To9).build()
    }
}

val networkModule = module {
    includes(commonNetworkModule)
    singleOf(::AndroidPlexClientIdentifier) { bind<PlexClientIdentifier>() }
}

val securityModule = module {
    includes(commonStoreModule)
    single(SettingsStoreQualifier) { createPreferencesDataStore(androidContext(), "settings") }
    single(CredentialsStoreQualifier) { createPreferencesDataStore(androidContext(), "credentials") }
    single<TokenCipher> { AesGcmTokenCipher { AndroidKeystoreKey.getOrCreate() } }
    single<CredentialStore> { DataStoreCredentialStore(get(CredentialsStoreQualifier), get()) }
    // Each server's connections (Plex) and last sync; no secrets.
    single(ServerStatusStoreQualifier) { createPreferencesDataStore(androidContext(), "server_status") }
}

val sourceModule = module {
    includes(commonSourceModule)
}

val repositoryModule = module {
    includes(commonServerModule)
    // Blocking I/O (Plex probes, plex.tv reads) for the common classes.
    single<CoroutineDispatcher>(BlockingIoQualifier) { Dispatchers.IO }
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
            // Looked up when a mirror is written, not when this is built (the player is in the playback module).
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
    // A downloaded book's files, for playing it from the phone (playback and the downloads share it).
    single<LocalBookFiles> { RoomLocalBookFiles(get(), AndroidLocalDisk, Dispatchers.IO, nowMs = System::currentTimeMillis) }
}

val browseModule = module {
    // The filters shared by Home, Library and Search; in memory only. A deleted server drops out of the server choice.
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

val playbackModule = module {
    // One controller for the app; the service itself finds its parts through Koin too.
    single<PlaybackController> { AndroidPlaybackController(androidContext(), get(), get(AppScopeQualifier), get()) }
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
    single<DayZone> { SystemDayZone }
    single<ListeningLog> {
        RoomListeningLog(get(), inTransaction = { block -> get<ChapterPlayerDatabase>().useWriterConnection { it.immediateTransaction { block() } } })
    }
    single<BookOwners> {
        val bookDao = get<BookDao>()
        BookOwners { id -> bookDao.getById(id)?.let { book -> BookOwner(book.serverId, ServerSource.entries.firstOrNull { it.name == book.source } ?: ServerSource.ABS) } }
    }
    single<ListeningClock> { AndroidListeningClock }
    single { ListeningRecorder(get(), get(), get(), get(AppScopeQualifier), get(), get()) }
    // Audiobookshelf's own listening history, imported once per server (and again from the stats page).
    single<ListeningImport> { ListeningStatsImporter(get(), getAll<LibrarySource>(), get(), get(), get(AppScopeQualifier), get()) }
    // Starts a book (position, saved speed, play) for details, Home and the player.
    singleOf(::PlaybackStarter)
    // A tapped notification asks the navigation host to open the player once.
    single { OpenPlayerRequest() }
}

val downloadModule = module {
    single(DownloadClientQualifier) { createDownloadHttpClient(get<Boolean>(DebuggableQualifier)) }
    single { SettingsDownloadLocation(androidContext(), get()) } binds arrayOf(DownloadLocation::class, DownloadStorage::class)
    single { FileDownloader(get(DownloadClientQualifier)) }
    // How each kind of server lists a book's files and is asked for one.
    single { AbsDownloadSource(get(), get()) } bind DownloadSource::class
    // A Plex file that cannot be reached re-probes the connections and carries on at the new address.
    single {
        PlexDownloadSource(
            get(), get(), get(DownloadClientQualifier),
            onConnectionFailure = { server, error -> get<PlexConnectionManager>().afterFailure(server, error, force = false, notify = true).retryWith },
            // The same address check as a sync: a plain http address not verified on this network never gets the token.
            prepare = { server -> get<PlexConnectionManager>().forTokenRequest(server) },
        )
    } bind DownloadSource::class
    // One per app: the worker runs it, and its progress flow feeds the notification (and later the UI).
    single {
        BookDownloader(get(), get(), get(), getAll<DownloadSource>(), get(), get(), limitBytes = { get<AppSettings>().downloadStorageLimitBytes.first() })
    } bind DownloadJobs::class
    single<LiveDownloadProgress> { get<BookDownloader>() }
    single<DownloadOverview> { RoomDownloadOverview(get(), get()) }
    single<DownloadScheduler> { WorkManagerDownloadScheduler(androidContext()) }
    // The player is looked up when a download is removed, not when this is built.
    single<DownloadRemovalListener> { DownloadRemovalListener { bookId -> get<PlaybackController>().releaseLocalFiles(bookId) } }
    single { DefaultDownloadController(get(), get(), get(), get(), get(), get(), get()) }
    single<DownloadController> { get<DefaultDownloadController>() }
    // The application starts it: whenever the phone comes online, fills in the file lengths older downloads lack.
    single {
        val bookDao = get<BookDao>()
        DownloadLengthFiller(get(), get(), getAll<DownloadSource>(), get(), get(), get(AppScopeQualifier), cachedAt = { id -> bookDao.getById(id)?.cachedAt })
    }
    // The application starts it: reschedules downloads after a reboot or process death.
    single { DownloadQueueRestorer(get(), get(AppScopeQualifier)) }
}

val deviceModule = module {
    single<ConnectivityMonitor> { AndroidConnectivityMonitor(androidContext()) }
    // The main activity lends it its permission launcher; the details screen asks before the first download.
    single { AndroidNotificationPermission(androidContext()) } bind NotificationPermission::class
}

val viewModelModule = module {
    // The Servers pages and the offline state (shared with iOS).
    includes(commonViewModelModule)
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
            merging = get(), merges = get(), servers = get()
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

val widgetModule = module {
    // What the home screen widgets show, and the Now Playing buttons (they act on the loaded book only).
    single { WidgetStates(get(), get(), get(), get(), get(), merging = get()) }
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
