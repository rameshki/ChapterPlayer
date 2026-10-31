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
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.playback.StreamAuthorizer
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.CoroutineScope
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
 * The player of a platform whose player takes fixed request headers (iOS: AVPlayer): [StreamAuthorizer] on the probe
 * client (no redirects followed; a Plex token the stream refuses is blanked as the Plex API calls do) and the
 * [PlaybackController] that [create] builds from the book repository, the stream authorizer, the app scope and the
 * downloaded files. Common so its wiring is verified by the host tests; iOS passes `IosPlaybackController.create`.
 */
fun headerPlayerModule(
    create: (BookRepository, StreamAuthorizer, CoroutineScope, LocalBookFiles) -> PlaybackController,
): Module = module {
    single {
        val servers = get<ServerRepository>()
        StreamAuthorizer(get(), get(), get(ProbeClientQualifier), onPlexRefused = { refused -> blankRefusedPlexToken(servers, refused) })
    }
    single<PlaybackController> { create(get(), get(), get(AppScopeQualifier), get()) }
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
