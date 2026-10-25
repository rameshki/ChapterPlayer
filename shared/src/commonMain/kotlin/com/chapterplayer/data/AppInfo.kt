package com.chapterplayer.data

/** Facts about the installed app that only the platform knows. The Android app provides it. */
interface AppInfo {
    /** The version name shown on the About page, e.g. "0.1.0". */
    val versionName: String
}
