package com.chapterplayer.di

import com.chapterplayer.BuildConfig
import org.koin.dsl.module

/**
 * Values only the app module knows. The shared module has no BuildConfig, so the
 * debug flag is provided here and read by the network module.
 */
val appModule = module {
    single(DebuggableQualifier) { BuildConfig.DEBUG }
}
