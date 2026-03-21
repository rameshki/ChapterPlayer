package com.chapterplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — entry point for Hilt dependency injection.
 * Annotating with @HiltAndroidApp triggers Hilt's code generation and
 * sets up the base application-level component.
 */
@HiltAndroidApp
class ChapterPlayerApp : Application()
