package com.chapterplayer

import android.app.Application
import com.chapterplayer.data.playback.PlaybackProgressSaver
import com.chapterplayer.data.bookmarks.BookmarkSyncer
import com.chapterplayer.data.bookmarks.HistoryRecorder
import com.chapterplayer.data.downloads.DownloadLengthFiller
import com.chapterplayer.data.downloads.DownloadQueueRestorer
import com.chapterplayer.data.progress.PendingProgressFlusher
import com.chapterplayer.data.progress.ProgressSyncer
import com.chapterplayer.data.progress.ServerProgressRefresher
import com.chapterplayer.di.appModule
import com.chapterplayer.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/** Application class: starts Koin, the dependency injection container, with every module. */
class ChapterPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.NONE)
            androidContext(this@ChapterPlayerApp)
            modules(sharedModules + appModule)
        }
        // Progress is saved for any playing book, whichever screen is open.
        GlobalContext.get().get<PlaybackProgressSaver>().start()
        // The server hears about it too, and what it missed (offline, errors) is delivered later.
        GlobalContext.get().get<ProgressSyncer>().start()
        GlobalContext.get().get<PendingProgressFlusher>().start()
        GlobalContext.get().get<ServerProgressRefresher>().start()
        // Bookmarks sync with the servers, and jumps in the playing book are remembered.
        GlobalContext.get().get<BookmarkSyncer>().start()
        GlobalContext.get().get<HistoryRecorder>().start()
        // Downloads that were waiting or running before a reboot or process death carry on.
        GlobalContext.get().get<DownloadQueueRestorer>().start()
        // Older downloads learn their file lengths from the server, so they can play offline.
        GlobalContext.get().get<DownloadLengthFiller>().start()
    }
}
