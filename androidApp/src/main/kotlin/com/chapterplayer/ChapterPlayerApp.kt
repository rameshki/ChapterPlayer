package com.chapterplayer

import android.app.Application
import com.chapterplayer.di.appModule
import com.chapterplayer.di.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
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
    }
}
