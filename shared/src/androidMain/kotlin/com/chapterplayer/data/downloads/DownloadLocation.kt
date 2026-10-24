package com.chapterplayer.data.downloads

import android.content.Context
import com.chapterplayer.domain.downloads.safeFileName
import java.io.File

/** Where downloads go. Step 3 adds the Settings choice (an SD card, for example); the default is the app's files folder. */
interface DownloadLocation {
    /** The folder that holds `downloads/`. */
    suspend fun rootDir(): File

    /** Free bytes on the volume of [rootDir]. */
    suspend fun freeBytes(): Long
}

class AppFilesDownloadLocation(private val context: Context) : DownloadLocation, DownloadStorage {
    override suspend fun rootDir(): File = context.filesDir
    override suspend fun freeBytes(): Long = context.filesDir.usableSpace
}

/** `<root>/downloads/<serverId>/<bookId>/`, each part made safe so it cannot leave the folder. */
fun bookDirectory(root: File, serverId: String, bookId: String): File =
    File(File(File(root, "downloads"), safeFileName(serverId)), safeFileName(bookId))
