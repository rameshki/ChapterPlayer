package com.chapterplayer.di

import androidx.room.Room
import com.chapterplayer.data.PlatformFeatures
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
import com.chapterplayer.data.stats.AndroidListeningClock
import com.chapterplayer.data.stats.ListeningClock
import com.chapterplayer.data.stats.SystemDayZone
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.domain.stats.DayZone
import com.chapterplayer.data.local.Migration8To9
import com.chapterplayer.data.remote.createDownloadHttpClient
import com.chapterplayer.data.sync.PlexConnectionManager
import com.chapterplayer.data.remote.plex.AndroidPlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.security.AesGcmTokenCipher
import com.chapterplayer.data.security.AndroidKeystoreKey
import com.chapterplayer.data.security.CredentialStore
import com.chapterplayer.data.security.DataStoreCredentialStore
import com.chapterplayer.data.security.TokenCipher
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.widgets.WidgetActions
import com.chapterplayer.data.widgets.WidgetRefresher
import com.chapterplayer.data.widgets.WidgetStates
import com.chapterplayer.data.widgets.WidgetHost
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.ui.widgets.GlanceWidgetHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
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
    includes(commonServerModule, commonRepositoryModule)
    // Blocking I/O (Plex probes, plex.tv reads) for the common classes.
    single<CoroutineDispatcher>(BlockingIoQualifier) { Dispatchers.IO }
    // A downloaded book's files, for playing it from the phone (playback and the downloads share it).
    single<LocalBookFiles> { RoomLocalBookFiles(get(), AndroidLocalDisk, Dispatchers.IO, nowMs = System::currentTimeMillis) }
}

val browseModule = module {
    includes(commonBrowseModule)
}

val playbackModule = module {
    includes(commonPlaybackModule)
    // One controller for the app; the service itself finds its parts through Koin too.
    single<PlaybackController> { AndroidPlaybackController(androidContext(), get(), get(AppScopeQualifier), get()) }
    single<DayZone> { SystemDayZone }
    single<ListeningClock> { AndroidListeningClock }
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
    // Android has every feature (iOS: browsing only for now).
    single { PlatformFeatures.Full }
}

val viewModelModule = module {
    // Every screen's view model (shared with iOS).
    includes(commonViewModelModule)
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
