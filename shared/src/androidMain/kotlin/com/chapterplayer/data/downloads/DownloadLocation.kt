package com.chapterplayer.data.downloads

import android.content.Context
import android.os.Environment
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.downloads.DownloadVolume
import com.chapterplayer.domain.downloads.INTERNAL_VOLUME_ID
import com.chapterplayer.domain.downloads.externalVolumeLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The location chosen in Settings: the app's files folder in internal storage (the default), or the app's own
 * folder on another volume such as an SD card (no storage permission needed). A chosen volume that is not
 * mounted now falls back to internal storage.
 */
class SettingsDownloadLocation(
    private val context: Context,
    private val settings: AppSettings,
) : DownloadLocation, DownloadStorage {

    override suspend fun rootDir(): String = rootFolder().path

    private suspend fun rootFolder(): File {
        val chosen = settings.downloadLocationId.first()
        if (chosen == null || chosen == INTERNAL_VOLUME_ID) return context.filesDir
        return withContext(Dispatchers.IO) { externalDirs().firstOrNull { idOf(it) == chosen } } ?: context.filesDir
    }

    override suspend fun freeBytes(): Long {
        val root = rootFolder()
        return withContext(Dispatchers.IO) { root.usableSpace }
    }

    override suspend fun volumes(): List<DownloadVolume> = withContext(Dispatchers.IO) {
        val internal = DownloadVolume(INTERNAL_VOLUME_ID, "Internal storage", context.filesDir.usableSpace)
        val externals = externalDirs()
        val labels = externalVolumeLabels(externals.map { removable(it) })
        listOf(internal) + externals.mapIndexed { i, dir -> DownloadVolume(idOf(dir), labels[i], dir.usableSpace) }
    }

    // getExternalFilesDirs lists the app's folder on every volume; an entry is null while its volume is unavailable.
    private fun externalDirs(): List<File> =
        context.getExternalFilesDirs(null).filterNotNull().filter { dir ->
            try {
                Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            } catch (_: IllegalArgumentException) {
                false
            }
        }

    private fun removable(dir: File): Boolean = try {
        Environment.isExternalStorageRemovable(dir)
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun idOf(dir: File) = EXTERNAL_PREFIX + dir.absolutePath

    private companion object {
        const val EXTERNAL_PREFIX = "external:"
    }
}
