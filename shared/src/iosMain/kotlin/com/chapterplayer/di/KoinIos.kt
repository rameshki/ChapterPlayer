package com.chapterplayer.di

import com.chapterplayer.data.IosAppInfo
import com.chapterplayer.data.IosPathProvider
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.connectivity.IosConnectivityMonitor
import com.chapterplayer.data.local.createIosDatabase
import com.chapterplayer.data.remote.plex.IosPlexClientIdentifier
import com.chapterplayer.data.security.KeychainCredentialStore
import com.chapterplayer.data.sync.PlexConnectionManager
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

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
    )
)

/** Every module iOS starts with, besides [commonModules]. */
val iosModules = listOf(iosPlatformModule, notYetOnIosModule)

/**
 * Starts Koin for the iOS app, once (later calls do nothing). Swift calls it as `KoinIosKt.doInitKoin()` from the app
 * entry; the root view controller calls it too, so the order of the two does not matter. Like Android's Application, it
 * then has Plex servers re-probed when the network changes.
 */
fun initKoin() = initKoin(iosModules) { koin ->
    koin.get<PlexConnectionManager>().watchNetwork(koin.get<CoroutineScope>(AppScopeQualifier), koin.get<ConnectivityMonitor>().currentNetwork)
}
