package com.chapterplayer.data

import android.app.ActivityManager

/**
 * Whether this app's process is the top app (a visible activity). Android 15 gives the dataSync foreground time back
 * and grants audio focus only then (or while a foreground service runs). False when it cannot be told.
 */
internal fun isAppInFront(): Boolean = try {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
} catch (_: Exception) {
    false
}
