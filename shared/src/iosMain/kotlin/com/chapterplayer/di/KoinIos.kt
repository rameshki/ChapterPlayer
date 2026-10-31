package com.chapterplayer.di

import com.chapterplayer.data.IosAppInfo
import com.chapterplayer.data.IosPathProvider
import com.chapterplayer.data.bookmarks.BookmarkSyncer
import com.chapterplayer.data.bookmarks.HistoryRecorder
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.connectivity.IosConnectivityMonitor
import com.chapterplayer.data.images.PlexImageServers
import com.chapterplayer.data.images.installCoverImageLoader
import com.chapterplayer.data.local.createIosDatabase
import com.chapterplayer.data.playback.IosPlaybackController
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.PlaybackProgressSaver
import com.chapterplayer.data.playback.StreamAuthorizer
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.progress.ProgressMirrorPusher
import com.chapterplayer.data.progress.ProgressSyncer
import com.chapterplayer.data.progress.ServerProgressRefresher
import com.chapterplayer.data.remote.plex.IosPlexClientIdentifier
import com.chapterplayer.data.security.KeychainCredentialStore
import com.chapterplayer.data.stats.IosDayZone
import com.chapterplayer.data.stats.IosListeningClock
import com.chapterplayer.data.stats.ListeningImport
import com.chapterplayer.data.stats.ListeningRecorder
import com.chapterplayer.data.sync.ForegroundSync
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.data.sync.PlexConnectionManager
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.Koin
import org.koin.dsl.module
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.NSObjectProtocol

/** What only iOS can make for the common modules: files, the database, the Keychain, the network monitor, the bundle. */
@OptIn(ExperimentalNativeApi::class)
val iosPlatformModule = platformModule(
    PlatformParts(
        // Debug builds of the framework log HTTP headers (auth headers hidden), like Android's debug builds.
        debuggable = Platform.isDebugBinary,
        paths = { IosPathProvider() },
        database = { paths -> createIosDatabase(paths) },
        // Tokens live in the Keychain (no separate credentials DataStore on iOS). Items left by an earlier install go first.
        credentials = { KeychainCredentialStore().also { it.clearAfterReinstall() } },
        plexClientId = { IosPlexClientIdentifier() },
        connectivity = { IosConnectivityMonitor() },
        appInfo = { IosAppInfo() },
        blockingIo = Dispatchers.IO,
        dayZone = { IosDayZone },
        listeningClock = { IosListeningClock },
    )
)

/**
 * The iPhone's player (Stage 5 step 4): AVPlayer through [IosPlaybackController], with the token in fixed request headers
 * after [StreamAuthorizer] checked the address (the probe client follows no redirects). Built on the main thread
 * ([startAppWide] asks for it first, from the app entry).
 */
val iosPlaybackModule = module {
    single { StreamAuthorizer(get(), get(), get(ProbeClientQualifier)) }
    single<PlaybackController> { IosPlaybackController.create(get(), get(), get(AppScopeQualifier), get()) }
}

/** Every module iOS starts with, besides [commonModules]: the platform's parts, the player and the download stand-ins (step 5). */
val iosModules = listOf(iosPlatformModule, iosPlaybackModule, notYetOnIosModule)

/** Kept so the observer lives as long as the app (the notification centre does not keep block observers alive for us). */
private var becameActiveObserver: NSObjectProtocol? = null

/**
 * Starts Koin for the iOS app, once (later calls do nothing). Swift calls it as `KoinIosKt.doInitKoin()` from the app
 * entry; the root view controller calls it too, so the order of the two does not matter. Then, like Android's
 * Application, it starts what runs app-wide without the player or downloads (see [startAppWide]).
 */
fun initKoin() = initKoin(iosModules) { koin -> startAppWide(koin) }

/**
 * The app-wide work of the iOS build: covers load with the Plex token header (never in a URL), Plex servers are
 * re-probed when the network changes, the library syncs while the app is in front (at start, when it becomes active
 * again, on a network change; no background sync yet), and the ABS progress refresh, the queued progress, bookmarks
 * and the listening history import run as on Android. Since Stage 5 step 4 the player is built here (so the lock screen
 * commands are registered at launch) and what follows it starts as on Android: progress saves, the live sync through the
 * server session (Plex timeline and scrobble included), mirrors to the other copies, jump history and listening time.
 */
private fun startAppWide(koin: Koin) {
    val scope = koin.get<CoroutineScope>(AppScopeQualifier)
    val network = koin.get<ConnectivityMonitor>().currentNetwork
    installCoverImageLoader(koin.get<PlexImageServers>())
    koin.get<PlaybackController>()
    koin.get<PlaybackProgressSaver>().start()
    koin.get<ProgressSyncer>().start()
    koin.get<ProgressMirrorPusher>().start()
    koin.get<HistoryRecorder>().start()
    koin.get<ListeningRecorder>().start()
    koin.get<PlexConnectionManager>().watchNetwork(scope, network)
    koin.get<LibrarySyncCoordinator>().start()
    koin.get<ServerProgressRefresher>().start()
    koin.get<PendingProgressFlusher>().start()
    koin.get<BookmarkSyncer>().start()
    koin.get<ListeningImport>().start()
    val foreground = koin.get<ForegroundSync>()
    foreground.start(network)
    becameActiveObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> foreground.onAppActive() }
}
