package com.chapterplayer.data

import platform.Foundation.NSBundle

/** The version the About page shows: the app bundle's `CFBundleShortVersionString` (MARKETING_VERSION in project.yml). */
class IosAppInfo : AppInfo {
    override val versionName: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown"
}
