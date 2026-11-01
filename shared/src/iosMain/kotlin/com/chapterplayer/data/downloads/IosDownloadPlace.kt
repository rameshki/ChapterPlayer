package com.chapterplayer.data.downloads

import com.chapterplayer.di.DownloadPlace
import com.chapterplayer.domain.downloads.DownloadVolume
import com.chapterplayer.domain.downloads.INTERNAL_VOLUME_ID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Where downloads go on the iPhone: `<Application Support>/downloads/<serverId>/<bookId>/`. Application Support is
 * never purged by iOS (Caches and tmp are), and the `downloads` folder is excluded from iCloud and computer backups
 * (set whenever it is created, see [IosDownloadFiles]). One place only, so Settings shows no location choice.
 */
class IosDownloadPlace(
    private val files: IosDownloadFiles = IosDownloadFiles,
    private val io: CoroutineDispatcher,
) : DownloadPlace {

    override suspend fun rootDir(): String = withContext(io) {
        // Creating it (or finding it) marks it as not backed up, also when something deleted and recreated it.
        if (files.makeDirectories(files.downloadsRoot)) files.excludeFromBackup(files.downloadsRoot)
        files.applicationSupport
    }

    override suspend fun freeBytes(): Long = withContext(io) { files.freeBytes(files.applicationSupport) }

    override suspend fun volumes(): List<DownloadVolume> = listOf(DownloadVolume(INTERNAL_VOLUME_ID, PLACE_LABEL, freeBytes()))

    private companion object {
        const val PLACE_LABEL = "This iPhone"
    }
}
