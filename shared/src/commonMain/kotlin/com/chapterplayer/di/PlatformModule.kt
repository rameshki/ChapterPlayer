package com.chapterplayer.di

import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.PathProvider
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.createPreferencesDataStore
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.security.CredentialStore
import com.chapterplayer.data.stats.ListeningClock
import com.chapterplayer.domain.stats.DayZone
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module
import org.koin.dsl.module

/** The parts a platform makes for [commonModules] when its files are found through a [PathProvider] (iOS). */
class PlatformParts(
    val debuggable: Boolean,
    val paths: () -> PathProvider,
    val database: (PathProvider) -> ChapterPlayerDatabase,
    val credentials: () -> CredentialStore,
    val plexClientId: () -> PlexClientIdentifier,
    val connectivity: () -> ConnectivityMonitor,
    val appInfo: () -> AppInfo,
    /** Dispatchers.IO, which common code cannot name. */
    val blockingIo: CoroutineDispatcher,
    /** The phone's time zone as it is set now, for the listening days. */
    val dayZone: () -> DayZone,
    /** A monotonic clock (counting sleep) and the wall clock, for the listening log. */
    val listeningClock: () -> ListeningClock,
)

/**
 * The platform module, with every binding (and qualifier) [commonModules] expect from a platform: the debug flag, the
 * files, the database, the two DataStores, the credential store, the Plex client id, connectivity, app info, the
 * blocking I/O dispatcher, the day zone and the listening clock. iOS builds it from its parts (`iosPlatformModule`); the host tests build it from fakes, so
 * a qualified binding that goes missing fails there. Android binds the same things in its own modules.
 */
fun platformModule(parts: PlatformParts): Module = module {
    single(DebuggableQualifier) { parts.debuggable }
    single<PathProvider> { parts.paths() }
    single<ChapterPlayerDatabase> { parts.database(get()) }
    single(SettingsStoreQualifier) {
        val paths = get<PathProvider>()
        createPreferencesDataStore { paths.preferencesFile("settings") }
    }
    // Each server's connections (Plex) and last sync; no secrets.
    single(ServerStatusStoreQualifier) {
        val paths = get<PathProvider>()
        createPreferencesDataStore { paths.preferencesFile("server_status") }
    }
    single<CredentialStore> { parts.credentials() }
    single<PlexClientIdentifier> { parts.plexClientId() }
    single<ConnectivityMonitor> { parts.connectivity() }
    single<AppInfo> { parts.appInfo() }
    single<CoroutineDispatcher>(BlockingIoQualifier) { parts.blockingIo }
    single<DayZone> { parts.dayZone() }
    single<ListeningClock> { parts.listeningClock() }
}
