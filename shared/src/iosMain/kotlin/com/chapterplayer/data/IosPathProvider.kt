package com.chapterplayer.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * The app's Application Support folder (created on first use): private to the app, kept by backups and app updates,
 * never shown in the Files app. DataStore files and the database sit directly in it, so no sub-folder has to exist.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPathProvider : PathProvider {

    private val root: String by lazy {
        val url = NSFileManager.defaultManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        requireNotNull(url?.path) { "The Application Support folder is not available" }
    }

    override fun preferencesFile(name: String): String = "$root/$name.preferences_pb"

    override fun databaseFile(name: String): String = "$root/$name"
}
