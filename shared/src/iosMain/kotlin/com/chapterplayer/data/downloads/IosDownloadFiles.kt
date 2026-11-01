package com.chapterplayer.data.downloads

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.IOException
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSUserDomainMask
import platform.posix.EINTR
import platform.posix.ENOSPC
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.SEEK_END
import platform.posix.errno
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.lseek
import platform.posix.open
import platform.posix.rename
import platform.posix.strerror
import kotlinx.cinterop.toKString

/**
 * The iPhone's files for the downloader. Writing goes through POSIX (`open`, `ftruncate`, `write`, `fsync`, `rename`,
 * which is atomic in one folder); folders, listing, deletion and free space through `NSFileManager` / `NSURL`. A full
 * disk is an [IOException] with "ENOSPC" in its text, so it reads as "Not enough space".
 *
 * Downloads live in `<Application Support>/downloads` ([downloadsRoot]): never purged by iOS (unlike Caches) and
 * excluded from iCloud and computer backups (`NSURLIsExcludedFromBackupKey`), set again whenever a folder under it is
 * created, so a recreated `downloads` folder is excluded too.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosDownloadFiles : DownloadFiles {

    private val manager get() = NSFileManager.defaultManager

    /** The Application Support folder: the root that holds `downloads/`. */
    val applicationSupport: String by lazy {
        val url = manager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
        requireNotNull(url?.path) { "The Application Support folder is not available" }
    }

    /** The folder every download is in. */
    val downloadsRoot: String get() = child(applicationSupport, DOWNLOADS_FOLDER)

    override fun length(path: String): Long? {
        val attributes = manager.attributesOfItemAtPath(path, error = null) ?: return null
        if (attributes[NSFileType] != NSFileTypeRegular) return null
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue
    }

    override fun isDirectory(path: String): Boolean = memScoped {
        val folder = alloc<BooleanVar>()
        manager.fileExistsAtPath(path, isDirectory = folder.ptr) && folder.value
    }

    override fun makeDirectories(path: String): Boolean {
        if (!isDirectory(path)) {
            manager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val exists = isDirectory(path)
        if (exists && isUnder(path, downloadsRoot)) excludeFromBackup(downloadsRoot)
        return exists
    }

    /** Marks the `downloads` folder (and so everything in it) as not to be backed up; best effort, true when set. */
    fun excludeFromBackup(path: String): Boolean {
        if (!isDirectory(path)) return false
        return NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    override fun openAppend(path: String, at: Long): DownloadSink {
        val fd = open(path, O_WRONLY or O_CREAT, 0x1A4) // rw-r--r--
        if (fd < 0) throw posixError("open")
        if (ftruncate(fd, at) != 0 || lseek(fd, 0, SEEK_END) < 0) {
            val error = posixError("truncate")
            platform.posix.close(fd)
            throw error
        }
        return PosixSink(fd)
    }

    override fun sync(path: String) {
        val fd = open(path, O_WRONLY)
        if (fd < 0) throw posixError("open")
        val ok = fsync(fd) == 0
        val error = if (ok) null else posixError("fsync")
        platform.posix.close(fd)
        if (error != null) throw error
    }

    override fun moveReplacing(from: String, to: String) {
        if (rename(from, to) != 0) throw posixError("rename")
    }

    override fun delete(path: String): Boolean {
        if (length(path) == null) return false
        return manager.removeItemAtPath(path, error = null)
    }

    override fun deleteRecursively(path: String) {
        if (manager.fileExistsAtPath(path)) manager.removeItemAtPath(path, error = null)
    }

    override fun list(path: String): List<String> =
        manager.contentsOfDirectoryAtPath(path, error = null)?.mapNotNull { it as? String }.orEmpty()

    override fun freeBytes(path: String): Long {
        // What iOS would free for an important download (purgeable space included), else the plain free size.
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), error.ptr)
            (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue?.let { if (it > 0L) return it }
        }
        val attributes = manager.attributesOfFileSystemForPath(path, error = null) ?: return 0L
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
    }

    override fun child(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

    override fun parent(path: String): String? {
        val trimmed = path.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        return when {
            slash < 0 -> null
            slash == 0 -> "/"
            else -> trimmed.substring(0, slash)
        }
    }

    override fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun isUnder(path: String, folder: String): Boolean = path == folder || path.startsWith("$folder/")

    /** Unbuffered writes straight to the descriptor (the downloader already writes 64 KB blocks). */
    private class PosixSink(private var fd: Int) : DownloadSink {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (fd < 0) throw IOException("write failed: closed")
            if (length == 0) return
            bytes.usePinned { pinned ->
                var written = 0
                while (written < length) {
                    val n = platform.posix.write(fd, pinned.addressOf(offset + written), (length - written).convert())
                    if (n < 0) {
                        if (errno == EINTR) continue
                        throw posixError("write")
                    }
                    written += n.toInt()
                }
            }
        }

        override fun flush() = Unit

        override fun sync() {
            if (fd < 0) return
            if (fsync(fd) != 0) throw posixError("fsync")
        }

        override fun close() {
            if (fd < 0) return
            val closing = fd
            fd = -1
            if (platform.posix.close(closing) != 0) throw posixError("close")
        }
    }
}

/** The last POSIX error as an [IOException]; a full disk says "ENOSPC". Never names a path. */
@OptIn(ExperimentalForeignApi::class)
private fun posixError(what: String): IOException {
    val code = errno
    val text = if (code == ENOSPC) "ENOSPC (No space left on device)" else strerror(code)?.toKString().orEmpty()
    return IOException("$what failed: $text")
}

internal actual fun platformDownloadFiles(): DownloadFiles = IosDownloadFiles
