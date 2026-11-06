package com.chapterplayer.data.images

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.chapterplayer.domain.covers.coverAuthority
import com.chapterplayer.domain.covers.coverCallerAllowed
import com.chapterplayer.domain.covers.parseCoverUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Serves book covers to Android Auto's lists (`content://<applicationId>.covers/cover/<bookId>`), so the car never gets
 * a server address or a token. Exported (the car reads it with its own user id) but each call checks the caller: only
 * this app and the trusted car packages ([coverCallerAllowed], the same list as browsing), everyone else gets a
 * [SecurityException]. Read only, one cover per book id of the library, no listing. The bytes come from
 * [CoverBytesLoader] (downloaded cover, else the book's own server), scaled, written to a cache file with a hashed name.
 * A call waits at most 4 s for a cover that is not ready; the fetch goes on in the background for the next request.
 */
class CoverProvider : ContentProvider(), KoinComponent {

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean = true

    private fun checkCaller(): Boolean {
        val context = context ?: return false
        val uid = Binder.getCallingUid()
        val packages = if (uid == Process.myUid()) listOf(context.packageName)
        else context.packageManager.getPackagesForUid(uid)?.toList()
        return coverCallerAllowed(packages, context.packageName)
    }

    private fun bookIdOf(uri: Uri): String? {
        val context = context ?: return null
        return parseCoverUri(uri.toString(), coverAuthority(context.packageName))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!checkCaller()) throw SecurityException("Covers are only for this app and the car")
        if (mode != "r") throw SecurityException("Covers are read only")
        val bookId = bookIdOf(uri) ?: throw FileNotFoundException("No such cover")
        val context = context ?: throw FileNotFoundException("No such cover")
        // Koin is started by the application; a call that comes in before that just finds no cover.
        // A binder thread waits only briefly: a cover not ready by then is still fetched in the background (one per book,
        // two at once), so the car's next request finds it in the memory cache.
        val bytes = try {
            val covers = get<CoverBytesLoader>()
            val loading = background.async { covers.load(bookId) }
            runBlocking { withTimeoutOrNull(OPEN_WAIT_MS) { loading.await() } }
        } catch (_: Exception) {
            null
        } ?: throw FileNotFoundException("No cover")
        val file = try {
            writeCacheFile(File(context.cacheDir, CACHE_FOLDER), bookId, bytes)
        } catch (_: Exception) {
            throw FileNotFoundException("No cover")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = if (checkCaller() && bookIdOf(uri) != null) "image/jpeg" else null

    // No listing and no rows: a cover is only ever opened.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (!checkCaller()) throw SecurityException("Covers are only for this app and the car")
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")

    private companion object {
        const val CACHE_FOLDER = "car_covers"
        const val OPEN_WAIT_MS = 4_000L
        const val STALE_PART_MS = 60_000L
        const val MAX_CACHE_FILES = 300

        /** Writes [bytes] to `<folder>/<sha256(bookId)>.jpg` (whole or not at all) and keeps the folder small. */
        fun writeCacheFile(folder: File, bookId: String, bytes: ByteArray): File {
            folder.mkdirs()
            // Temp files a killed process left behind (a writer's own is younger than a minute).
            val now = System.currentTimeMillis()
            folder.listFiles { f -> f.name.endsWith(".part") && now - f.lastModified() > STALE_PART_MS }.orEmpty().forEach { it.delete() }
            val target = File(folder, hashed(bookId) + ".jpg")
            val temp = File.createTempFile("cover", ".part", folder)
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) throw FileNotFoundException("No cover")
            } finally {
                temp.delete()
            }
            val files = folder.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
            if (files.size > MAX_CACHE_FILES) {
                files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHE_FILES).forEach { if (it != target) it.delete() }
            }
            return target
        }

        fun hashed(bookId: String): String =
            MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
