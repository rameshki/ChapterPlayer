package com.chapterplayer.data.images

import com.chapterplayer.data.downloads.DOWNLOADED_COVER_FILE
import com.chapterplayer.data.downloads.DownloadFiles
import com.chapterplayer.data.local.dao.DownloadDao
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.domain.covers.COVER_MAX_BYTES
import com.chapterplayer.domain.covers.CoverAuth
import com.chapterplayer.domain.covers.coverRequestAuth
import com.chapterplayer.domain.covers.isCoverBookId
import com.chapterplayer.domain.plex.PLEX_TOKEN_HEADER
import com.chapterplayer.domain.plex.plexTokenFor
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where a book's cover is: its server and the cover's address there (no token in it). */
data class BookCover(val serverId: String, val coverUrl: String?)

/**
 * A book's cover as small JPEG bytes, for the media notification, the lock screen and the car (which cannot load the
 * server's address: it needs the token). A downloaded book's own `cover.jpg` comes first, so it works offline; else the
 * cover is fetched from the book's own server only ([coverRequestAuth]): the Audiobookshelf bearer or the Plex token
 * goes in a header, never in an address, and [client] drops both on a redirect to another host. Every cover is scaled
 * by [downscale] (at most 512 px on the long side) and kept in a small memory cache.
 *
 * A failure of any kind (offline, an error answer, a refused token, not an image) is just no cover: nothing is thrown,
 * a refused token is not refreshed, and the book is not asked for again for [failureBackoffMs]. A book whose server was
 * removed has no cover. One fetch per book at a time, at most two at once.
 */
class CoverBytesLoader(
    private val coverOf: suspend (bookId: String) -> BookCover?,
    private val server: suspend (serverId: String) -> ServerConfig?,
    private val authorizer: AbsAuthorizer,
    private val localCover: suspend (bookId: String) -> ByteArray?,
    private val downscale: (ByteArray) -> ByteArray?,
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
    private val nowMs: () -> Long,
    private val cacheSize: Int = 8,
    private val failureBackoffMs: Long = 60_000,
    private val timeoutMs: Long = 15_000,
) {
    private val guard = Mutex()
    private val cache = LinkedHashMap<String, ByteArray>()
    private val failedAt = HashMap<String, Long>()
    private val perBook = HashMap<String, Mutex>()
    private val fetches = Semaphore(2)

    /** The cover of [bookId], or null when there is none or it cannot be had now. */
    suspend fun load(bookId: String): ByteArray? {
        if (!isCoverBookId(bookId)) return null
        val lock = guard.withLock {
            known(bookId)?.let { return it.bytes }
            perBook.getOrPut(bookId) { Mutex() }
        }
        return lock.withLock {
            guard.withLock { known(bookId) }?.let { return@withLock it.bytes }
            val bytes = try {
                find(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            guard.withLock {
                perBook.remove(bookId)
                if (bytes != null) {
                    failedAt.remove(bookId)
                    cache.remove(bookId)
                    cache[bookId] = bytes
                    while (cache.size > cacheSize) cache.remove(cache.keys.first())
                } else {
                    failedAt[bookId] = nowMs()
                }
            }
            bytes
        }
    }

    private class Known(val bytes: ByteArray?)

    /** What is already known about [bookId] (call under [guard]): the cached cover, a recent failure, or null. */
    private fun known(bookId: String): Known? {
        cache.remove(bookId)?.let { bytes ->
            cache[bookId] = bytes
            return Known(bytes)
        }
        val failed = failedAt[bookId] ?: return null
        if (nowMs() - failed < failureBackoffMs) return Known(null)
        failedAt.remove(bookId)
        return null
    }

    private suspend fun find(bookId: String): ByteArray? {
        val book = coverOf(bookId) ?: return null
        val server = server(book.serverId) ?: return null
        withContext(io) { localCover(bookId) }?.let { raw ->
            withContext(io) { downscale(raw) }?.let { return it }
        }
        val url = book.coverUrl?.takeIf { it.isNotBlank() } ?: return null
        val auth = coverRequestAuth(url, server) ?: return null
        val raw = fetches.withPermit {
            withTimeoutOrNull(timeoutMs) {
                when (auth) {
                    CoverAuth.NONE -> fetch(url, emptyList())
                    CoverAuth.ABS_BEARER -> authorizer.authorized(server) { bearer -> fetch(url, listOf(HttpHeaders.Authorization to bearer)) }
                    CoverAuth.PLEX_TOKEN -> {
                        val token = plexTokenFor(url, listOf(server)) ?: return@withTimeoutOrNull null
                        fetch(url, listOf(PLEX_TOKEN_HEADER to token))
                    }
                }
            }
        } ?: return null
        return withContext(io) { downscale(raw) }
    }

    /** The body of a 200 answer of at most [COVER_MAX_BYTES], else null. A 401 is not thrown, so nothing is refreshed. */
    private suspend fun fetch(url: String, headers: List<Pair<String, String>>): ByteArray? =
        client.prepareGet(url) { for ((name, value) in headers) header(name, value) }.execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute null
            val length = response.contentLength()
            if (length != null && length > COVER_MAX_BYTES) return@execute null
            val channel = response.bodyAsChannel()
            val out = ArrayList<ByteArray>()
            var total = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > COVER_MAX_BYTES) return@execute null
                out += buffer.copyOf(read)
            }
            if (total == 0L) return@execute null
            val bytes = ByteArray(total.toInt())
            var at = 0
            for (part in out) {
                part.copyInto(bytes, at)
                at += part.size
            }
            bytes
        }
}

/** The `cover.jpg` the downloader saved in a downloaded book's folder, or null when the book has no download files. */
suspend fun downloadedCoverPath(downloads: DownloadDao, files: DownloadFiles, bookId: String): String? {
    val file = downloads.getFiles(bookId).firstOrNull { it.path.isNotBlank() } ?: return null
    val folder = files.parent(file.path) ?: return null
    return files.child(folder, DOWNLOADED_COVER_FILE)
}
