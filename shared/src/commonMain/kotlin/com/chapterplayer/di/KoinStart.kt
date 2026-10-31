package com.chapterplayer.di

import com.chapterplayer.data.PlatformFeatures
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.downloads.NoDownloadStorage
import com.chapterplayer.data.downloads.NoLiveDownloadProgress
import com.chapterplayer.data.downloads.NoLocalBookFiles
import com.chapterplayer.data.downloads.NotYetOnIosDownloadController
import com.chapterplayer.data.downloads.RoomDownloadOverview
import com.chapterplayer.data.permissions.NoNotificationPermission
import com.chapterplayer.data.permissions.NotificationPermission
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * The bindings iOS cannot make for real yet: no downloader until Stage 5 step 5. Downloads cannot start ("Downloads
 * are not available on iOS yet"), there are no downloaded files, no download storage and no notification prompt; the
 * download overview reads the (empty) download tables. [PlatformFeatures.IosStreaming] tells the screens. The player
 * itself is iOS's own (`iosPlaybackModule`, AVPlayer) since step 4.
 */
val notYetOnIosModule = module {
    single<PlatformFeatures> { PlatformFeatures.IosStreaming }
    single<DownloadController> { NotYetOnIosDownloadController() }
    single<DownloadStorage> { NoDownloadStorage }
    single<DownloadOverview> { RoomDownloadOverview(get(), NoLiveDownloadProgress) }
    single<LocalBookFiles> { NoLocalBookFiles }
    single<NotificationPermission> { NoNotificationPermission }
}

/**
 * Starts Koin with [commonModules] plus the platform's own modules, once: a second call (the app entry and the root
 * view controller both ask) does nothing. [onStarted] runs after the first start only (the app-wide watches).
 * Android starts Koin itself in its Application with `sharedModules`.
 */
fun initKoin(platformModules: List<Module>, onStarted: (Koin) -> Unit = {}) {
    if (KoinPlatformTools.defaultContext().getOrNull() != null) return
    val koin = startKoin { modules(commonModules + platformModules) }.koin
    onStarted(koin)
}
