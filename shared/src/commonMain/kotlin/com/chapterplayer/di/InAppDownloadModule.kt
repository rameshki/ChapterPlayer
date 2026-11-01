package com.chapterplayer.di

import com.chapterplayer.data.PlatformFeatures
import com.chapterplayer.data.downloads.AbsDownloadSource
import com.chapterplayer.data.downloads.BookDownloader
import com.chapterplayer.data.downloads.DefaultDownloadController
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadFiles
import com.chapterplayer.data.downloads.DownloadFilesLocalDisk
import com.chapterplayer.data.downloads.DownloadJobs
import com.chapterplayer.data.downloads.DownloadLengthFiller
import com.chapterplayer.data.downloads.DownloadLocation
import com.chapterplayer.data.downloads.DownloadOverview
import com.chapterplayer.data.downloads.DownloadQueueRestorer
import com.chapterplayer.data.downloads.DownloadRemovalListener
import com.chapterplayer.data.downloads.DownloadScheduler
import com.chapterplayer.data.downloads.DownloadSource
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.data.downloads.FileDownloader
import com.chapterplayer.data.downloads.LiveDownloadProgress
import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.downloads.PlexDownloadSource
import com.chapterplayer.data.downloads.RoomDownloadOverview
import com.chapterplayer.data.downloads.RoomLocalBookFiles
import com.chapterplayer.data.local.dao.BookDao
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.permissions.DownloadNotices
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.remote.createDownloadHttpClient
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.sync.PlexConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Clock

/** A place downloads go that also reports its storage (iOS: the app's Application Support folder). */
interface DownloadPlace : DownloadLocation, DownloadStorage

/**
 * What an in-process scheduler (iOS: `IosDownloadScheduler`) is built from, all resolved by [inAppDownloadModule] so the
 * host tests check them: the app scope, the downloader, connectivity, a book's title, the controller's queue restore
 * (looked up when it runs: the controller itself needs the scheduler) and the notices.
 */
class DownloadSchedulerParts(
    val scope: CoroutineScope,
    val downloader: BookDownloader,
    val connectivity: ConnectivityMonitor,
    val titleOf: suspend (bookId: String) -> String?,
    val restore: suspend () -> Unit,
    val notices: DownloadNotices,
)

/**
 * The downloads of a platform that runs them in the app's own process (iOS): the same downloader, sources, controller,
 * length filler and local files as Android's `downloadModule`, over the platform's [files], [place], [scheduler] and
 * [notifications]. Common so the host tests verify every binding and resolve the scheduler's parts (KoinModulesTest);
 * iOS passes only constructors of its own classes, which are compile-checked (they cannot be built off an iPhone).
 * [features] is what the screens may offer on this build.
 */
fun inAppDownloadModule(
    features: PlatformFeatures,
    files: () -> DownloadFiles,
    place: () -> DownloadPlace,
    scheduler: (DownloadSchedulerParts) -> DownloadScheduler,
    notifications: () -> DownloadNotices,
): Module = module {
    single<PlatformFeatures> { features }
    single(DownloadClientQualifier) { createDownloadHttpClient(get<Boolean>(DebuggableQualifier)) }
    single<DownloadFiles> { files() }
    single<DownloadPlace> { place() }
    single<DownloadLocation> { get<DownloadPlace>() }
    single<DownloadStorage> { get<DownloadPlace>() }
    single { FileDownloader(get(DownloadClientQualifier), files = get()) }
    // How each kind of server lists a book's files and is asked for one (as on Android).
    single { AbsDownloadSource(get(), get()) } bind DownloadSource::class
    single {
        PlexDownloadSource(
            get(), get(), get(DownloadClientQualifier),
            onConnectionFailure = { server, error -> get<PlexConnectionManager>().afterFailure(server, error, force = false, notify = true).retryWith },
            prepare = { server -> get<PlexConnectionManager>().forTokenRequest(server) },
        )
    } bind DownloadSource::class
    single {
        BookDownloader(
            get(), get(), get(), getAll<DownloadSource>(), get(), get(),
            limitBytes = { get<AppSettings>().downloadStorageLimitBytes.first() }, io = get(BlockingIoQualifier),
        )
    } bind DownloadJobs::class
    single<LiveDownloadProgress> { get<BookDownloader>() }
    single<DownloadOverview> { RoomDownloadOverview(get(), get()) }
    single<DownloadScheduler> {
        scheduler(
            DownloadSchedulerParts(
                scope = get(AppScopeQualifier),
                downloader = get(),
                connectivity = get(),
                titleOf = { id -> get<BookDao>().getById(id)?.title },
                restore = { get<DefaultDownloadController>().restoreQueue() },
                notices = get(),
            )
        )
    }
    // The player is looked up when a download is removed, not when this is built.
    single<DownloadRemovalListener> { DownloadRemovalListener { bookId -> get<PlaybackController>().releaseLocalFiles(bookId) } }
    single { DefaultDownloadController(get(), get(), get(), get(), get(), get(), get(), files = get(), io = get(BlockingIoQualifier)) }
    single<DownloadController> { get<DefaultDownloadController>() }
    single {
        val bookDao = get<BookDao>()
        DownloadLengthFiller(get(), get(), getAll<DownloadSource>(), get(), get(), get(AppScopeQualifier), cachedAt = { id -> bookDao.getById(id)?.cachedAt })
    }
    single { DownloadQueueRestorer(get(), get(AppScopeQualifier)) }
    // A downloaded book's files, for playing it from the phone.
    single<LocalBookFiles> {
        RoomLocalBookFiles(get(), DownloadFilesLocalDisk(get()), get(BlockingIoQualifier), nowMs = { Clock.System.now().toEpochMilliseconds() })
    }
    single<DownloadNotices> { notifications() }
    single<NotificationPermission> { get<DownloadNotices>() }
}
