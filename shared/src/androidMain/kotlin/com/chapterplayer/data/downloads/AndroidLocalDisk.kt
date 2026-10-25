package com.chapterplayer.data.downloads

import java.io.File

/** The phone's file system, for [RoomLocalBookFiles]. */
object AndroidLocalDisk : LocalDisk {
    override fun fileLength(path: String): Long? = File(path).takeIf { it.isFile }?.length()

    // An SD card that is not mounted takes the whole folder with it.
    override fun folderExists(path: String): Boolean = File(path).absoluteFile.parentFile?.isDirectory == true
}
