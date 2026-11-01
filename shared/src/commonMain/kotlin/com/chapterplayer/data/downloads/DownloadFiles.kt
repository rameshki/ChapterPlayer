package com.chapterplayer.data.downloads

import com.chapterplayer.domain.downloads.safeFileName
import kotlinx.io.IOException

/**
 * What the downloader asks of the phone's file system, so the downloader itself is common code. Paths are absolute
 * strings as the platform writes them (they are stored in `download_files.path`). Blocking: callers run it off the main
 * thread. Failures to write throw [IOException] (with "ENOSPC" in the message for a full disk, see [storageMessage]).
 * Android: java.io / nio (`AndroidDownloadFiles`); iOS: POSIX and `NSFileManager` (`IosDownloadFiles`).
 */
interface DownloadFiles {
    /** The size of the regular file at [path], or null when there is none (or it is a folder). */
    fun length(path: String): Long?

    fun isDirectory(path: String): Boolean

    /** Creates the folder and its parents; true when it exists afterwards. */
    fun makeDirectories(path: String): Boolean

    /** Cuts the file at [path] to [at] bytes (creating it when missing) and opens it for writing at its end. */
    fun openAppend(path: String, at: Long): DownloadSink

    /** Flushes the file at [path] to the disk (it exists and is complete). */
    fun sync(path: String)

    /** Moves [from] onto [to], replacing it; atomic where the file system allows. */
    fun moveReplacing(from: String, to: String)

    /** Deletes the file at [path]; false when nothing was deleted. */
    fun delete(path: String): Boolean

    /** Deletes [path] and everything under it; nothing when it is not there. */
    fun deleteRecursively(path: String)

    /** The names of the entries in the folder [path]; empty when it is not a folder. */
    fun list(path: String): List<String>

    /** Free bytes on the volume that holds [path] (for new downloads). */
    fun freeBytes(path: String): Long

    /** [name] inside the folder [parent]. */
    fun child(parent: String, name: String): String

    /** The folder that holds [path], or null at the top. */
    fun parent(path: String): String?

    /** The last part of [path]. */
    fun name(path: String): String
}

/** An open file being written. Not thread safe; one writer per file. */
interface DownloadSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Pushes buffered bytes to the file. */
    fun flush()

    /** Flushes, then forces the file's bytes to the disk. */
    fun sync()

    /** Flushes and closes; safe to call twice. */
    fun close()
}

/** A [LocalDisk] over [files], for [RoomLocalBookFiles]. A book folder that cannot be seen means "not playable now". */
class DownloadFilesLocalDisk(private val files: DownloadFiles) : LocalDisk {
    override fun fileLength(path: String): Long? = files.length(path)

    override fun folderExists(path: String): Boolean = files.parent(path)?.let(files::isDirectory) == true
}

/** The platform's [DownloadFiles] (the default of the common download classes). */
internal expect fun platformDownloadFiles(): DownloadFiles

/** `<root>/downloads/<serverId>/<bookId>/`, each part made safe so it cannot leave the folder. */
fun DownloadFiles.bookDirectory(root: String, serverId: String, bookId: String): String =
    child(child(child(root, DOWNLOADS_FOLDER), safeFileName(serverId)), safeFileName(bookId))

/** The folder under the download root that holds every download. */
const val DOWNLOADS_FOLDER = "downloads"

/** Where NEW downloads go. A download already planned stays in the folder its file rows name. */
interface DownloadLocation {
    /** The folder that holds `downloads/`. */
    suspend fun rootDir(): String

    /** Free bytes on the volume of [rootDir]. */
    suspend fun freeBytes(): Long
}
