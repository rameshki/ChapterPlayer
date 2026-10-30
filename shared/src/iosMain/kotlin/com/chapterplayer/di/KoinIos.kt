package com.chapterplayer.di

import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.IosAppInfo
import com.chapterplayer.data.IosPathProvider
import com.chapterplayer.data.PathProvider
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.connectivity.IosConnectivityMonitor
import com.chapterplayer.data.createPreferencesDataStore
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.local.createIosDatabase
import com.chapterplayer.data.remote.plex.IosPlexClientIdentifier
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.security.CredentialStore
import com.chapterplayer.data.security.KeychainCredentialStore
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.dsl.module

/** What only iOS can make for the common modules: files, the database, the Keychain, the network monitor, the bundle. */
@OptIn(ExperimentalNativeApi::class)
val iosPlatformModule = module {
    // Debug builds of the framework log HTTP headers (auth headers hidden), like Android's debug builds.
    single(DebuggableQualifier) { Platform.isDebugBinary }
    single<PathProvider> { IosPathProvider() }
    single<ChapterPlayerDatabase> { createIosDatabase(get()) }
    single(SettingsStoreQualifier) {
        val paths = get<PathProvider>()
        createPreferencesDataStore { paths.preferencesFile("settings") }
    }
    // Each server's connections (Plex) and last sync; no secrets.
    single(ServerStatusStoreQualifier) {
        val paths = get<PathProvider>()
        createPreferencesDataStore { paths.preferencesFile("server_status") }
    }
    // Tokens live in the Keychain (no separate credentials DataStore on iOS).
    single<CredentialStore> { KeychainCredentialStore() }
    single<PlexClientIdentifier> { IosPlexClientIdentifier() }
    single<ConnectivityMonitor> { IosConnectivityMonitor() }
    single<AppInfo> { IosAppInfo() }
    single<CoroutineDispatcher>(BlockingIoQualifier) { Dispatchers.IO }
}

/** Every module iOS starts with, besides [commonModules]. */
val iosModules = listOf(iosPlatformModule, notYetOnIosModule)

/**
 * Starts Koin for the iOS app, once (later calls do nothing). Swift calls it as `KoinIosKt.doInitKoin()` from the app
 * entry; the root view controller calls it too, so the order of the two does not matter.
 */
fun initKoin() = initKoin(iosModules)
