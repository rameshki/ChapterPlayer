package com.chapterplayer.data.downloads

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.playback.SIGN_IN_AGAIN_TEXT
import com.chapterplayer.data.playback.sameHost
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.domain.downloads.fileExtension
import com.chapterplayer.domain.downloads.finalFileName
import com.chapterplayer.domain.downloads.partFileName
import com.chapterplayer.domain.downloads.validResumeOffset
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One audio file to fetch into [directory]: the file [ino] (the server's file id) of the app's book [bookId], asked for
 * through [source]. [fileName] only gives the extension.
 */
class FileRequest(
    val source: DownloadSource,
    val server: ServerConfig,
    val bookId: String,
    val ino: String,
    val fileName: String?,
    val directory: File,
    val totalBytes: Long,
    /** What Room says was written; checked against the `.part` file before resuming. */
    val recordedDoneBytes: Long,
)

sealed interface FileResult {
    /** The file is complete at [file]. */
    data class Done(val file: File) : FileResult

    /** Try again later (network, server error, stall, body cut short); [doneBytes] are safely in the `.part` file. */
    data class Retry(val doneBytes: Long) : FileResult

    /**
     * Do not retry. [message] is fixed text that is safe to show: never an address or a token. [replan]: the
     * server's file list no longer matches (file gone, wrong size), so the book's file list should be fetched again.
     */
    data class Failed(val message: String, val signInRequired: Boolean = false, val replan: Boolean = false) : FileResult
}

/** Where a file is written while downloading and where it ends up. Both are always inside the book folder. */
internal class DownloadTargets(val part: File, val final: File)

internal fun targetsFor(directory: File, ino: String, fileName: String?): DownloadTargets {
    val ext = fileExtension(fileName)
    val part = File(directory, partFileName(ino, ext))
    val final = File(directory, finalFileName(ino, ext))
    val folder = directory.canonicalFile
    check(part.canonicalFile.parentFile == folder && final.canonicalFile.parentFile == folder) { "Unsafe file name" }
    return DownloadTargets(part, final)
}

/**
 * Downloads one file with `Range` resume into a `.part` file, then renames it. No overall timeout:
 * a request that gets no bytes for [stallMs] is cancelled and reported as [FileResult.Retry].
 * The address and the credential come from the request's [DownloadSource] per request (Audiobookshelf refreshes once
 * on a 401; a refused credential fails with "Sign in again"). A request that cannot reach the server asks the source
 * for a new address ([DownloadSource.afterConnectionFailure]) and, when it gets one, resumes the same `.part` file there
 * at once. Redirects are followed only on the same server. Tokens and addresses never go into a result or a message.
 */
class FileDownloader(
    private val client: HttpClient,
    private val stallMs: Long = 60_000,
    private val progressIntervalMs: Long = 500,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Opens the `.part` file for appending; tests pass one that fails like a full disk. */
    private val openPart: (File) -> FileOutputStream = { FileOutputStream(it, true) },
) {

    /** [onProgress] gets the bytes in the file so far, at most every [progressIntervalMs] and at the end of each request. */
    suspend fun download(request: FileRequest, onProgress: (doneBytes: Long) -> Unit): FileResult {
        val total = request.totalBytes
        if (total <= 0L) return FileResult.Failed(WRONG_SIZE)
        val targets = try {
            targetsFor(request.directory, request.ino, request.fileName)
        } catch (_: IllegalStateException) {
            return FileResult.Failed(UNSAFE_NAME)
        }
        val part = targets.part
        if (targets.final.isFile && targets.final.length() == total) {
            onProgress(total)
            return FileResult.Done(targets.final)
        }
        if (!request.directory.isDirectory && !request.directory.mkdirs()) return FileResult.Failed(NO_FOLDER)

        var server = request.server
        var recorded = request.recordedDoneBytes
        var restarts = 0
        var switches = 0
        while (true) {
            val offset = validResumeOffset(if (part.isFile) part.length() else 0L, recorded, total)
            var used: ServerConfig? = null
            val outcome = if (offset == total) {
                Outcome.Complete
            } else {
                try {
                    request.source.authorized(server, request.bookId, request.ino) { fetch ->
                        used = fetch.server
                        fetch(fetch, part, offset, total, onProgress)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SignInRequiredException) {
                    return FileResult.Failed(SIGN_IN_AGAIN_TEXT, signInRequired = true)
                } catch (e: StorageException) {
                    return FileResult.Failed(e.userMessage)
                } catch (_: DownloadFileGoneException) {
                    return FileResult.Failed(NOT_FOUND, replan = true)
                } catch (_: HttpStatusException) {
                    // Only the token refresh throws this here (the transfer answers with an outcome). A refused
                    // refresh (401/403) is already a SignInRequiredException; anything else (429, 5xx) may pass.
                    Outcome.Again
                } catch (e: IOException) {
                    connectionFailed(request, used ?: server, e, switches)
                } catch (e: Exception) {
                    // Engine errors that are not IOExceptions (an unresolved address and the like) are handled alike.
                    connectionFailed(request, used ?: server, e, switches)
                }
            }
            if (outcome is Outcome.Switch) {
                // The server is at another address now: carry on there from what is safely in the `.part` file.
                server = outcome.server
                switches++
                recorded = currentLength(part).coerceAtMost(total)
                continue
            }
            when (outcome) {
                Outcome.Complete -> return try {
                    finish(targets, total, onProgress)
                } catch (e: IOException) {
                    FileResult.Failed(storageMessage(e))
                }
                Outcome.Again -> return FileResult.Retry(currentLength(part))
                Outcome.Restart -> {
                    part.delete()
                    recorded = 0L
                    if (++restarts > MAX_RESTARTS) return FileResult.Retry(0L)
                }
                Outcome.WrongSize -> {
                    part.delete()
                    return FileResult.Failed(WRONG_SIZE, replan = true)
                }
                is Outcome.Refused -> return FileResult.Failed(outcome.message, replan = outcome.replan)
                is Outcome.Switch -> Unit // handled above
            }
        }
    }

    /** A request that could not reach the server: [Outcome.Switch] when the source names a new address, else later. */
    private suspend fun connectionFailed(request: FileRequest, used: ServerConfig, error: Exception, switches: Int): Outcome {
        if (switches >= MAX_SWITCHES) return Outcome.Again
        val next = try {
            request.source.afterConnectionFailure(used, error)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // choosing an address failed too: the usual retry later
        }
        return if (next != null) Outcome.Switch(next) else Outcome.Again
    }

    private sealed interface Outcome {
        data object Complete : Outcome
        data object Again : Outcome
        data object Restart : Outcome
        data object WrongSize : Outcome
        data class Refused(val message: String, val replan: Boolean = false) : Outcome
        /** Try again at once on [server]'s new address. */
        class Switch(val server: ServerConfig) : Outcome
    }

    /** One request (plus same-server redirects). Throws [HttpStatusException] 401 so the source refreshes or gives up. */
    private suspend fun fetch(
        request: FileFetch, part: File, offset: Long, total: Long, onProgress: (Long) -> Unit,
    ): Outcome {
        val firstUrl = request.url
        var url = firstUrl
        repeat(MAX_REDIRECTS + 1) {
            val next = withHeaderTimeout { headersArrived ->
                client.prepareGet(url) {
                    for ((name, value) in request.headers) header(name, value)
                    if (offset > 0L) header(HttpHeaders.Range, "bytes=$offset-")
                }.execute { response ->
                    headersArrived()
                    answer(response, part, offset, total, onProgress)
                }
            } ?: return Outcome.Again
            if (next !is Redirect) return next.outcome!!
            val target = resolve(url, next.location) ?: return Outcome.Refused(MOVED)
            if (!sameHost(firstUrl, target)) return Outcome.Refused(MOVED)
            url = target
        }
        return Outcome.Refused(MOVED)
    }

    private open class Step(val outcome: Outcome?)
    private class Redirect(val location: String) : Step(null)

    private suspend fun answer(response: HttpResponse, part: File, offset: Long, total: Long, onProgress: (Long) -> Unit): Step {
        val status = response.status
        return when {
            status == HttpStatusCode.PartialContent -> {
                val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                when {
                    range == null || range.first != offset -> Step(Outcome.Restart)
                    range.second != null && range.second != total -> Step(Outcome.WrongSize)
                    else -> Step(receive(response, part, startAt = offset, total, onProgress))
                }
            }
            status == HttpStatusCode.OK -> {
                val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length != total) Step(Outcome.WrongSize)
                else Step(receive(response, part, startAt = 0L, total, onProgress)) // Range ignored: from zero, never appended
            }
            status == HttpStatusCode.RequestedRangeNotSatisfiable ->
                Step(if (part.isFile && part.length() == total) Outcome.Complete else Outcome.Restart)
            status == HttpStatusCode.Unauthorized -> throw HttpStatusException(status)
            status.value in 300..399 -> {
                val location = response.headers[HttpHeaders.Location]
                if (location.isNullOrBlank()) Step(Outcome.Refused(MOVED)) else Redirect(location)
            }
            status.value >= 500 || status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests ->
                Step(Outcome.Again)
            status == HttpStatusCode.Forbidden -> Step(Outcome.Refused(FORBIDDEN))
            status == HttpStatusCode.NotFound -> Step(Outcome.Refused(NOT_FOUND, replan = true))
            else -> Step(Outcome.Refused(REFUSED))
        }
    }

    /**
     * Writes the body into [part] from [startAt] (the file is cut there first). A failed read is the network
     * (an IOException, retried); a failed write is the storage (a [StorageException], never retried).
     */
    private suspend fun receive(response: HttpResponse, part: File, startAt: Long, total: Long, onProgress: (Long) -> Unit): Outcome {
        val stream = disk {
            RandomAccessFile(part, "rw").use { it.setLength(startAt) }
            openPart(part)
        }
        val channel = response.bodyAsChannel()
        var done = startAt
        var lastReport = nowMs()
        val buffer = ByteArray(BUFFER_BYTES)
        val out = BufferedOutputStream(stream, BUFFER_BYTES)
        try {
            try {
                while (true) {
                    val read = withTimeoutOrNull(stallMs) { channel.readAvailable(buffer, 0, buffer.size) }
                        ?: return Outcome.Again.also { disk { out.flush() }; onProgress(done) } // stalled: leaving cancels the request
                    if (read < 0) break
                    if (done + read > total) return Outcome.WrongSize
                    disk { out.write(buffer, 0, read) }
                    done += read
                    val now = nowMs()
                    if (now - lastReport >= progressIntervalMs) {
                        lastReport = now
                        onProgress(done)
                    }
                }
                disk {
                    out.flush()
                    if (done == total) stream.fd.sync()
                }
            } finally {
                try {
                    out.close() // flushes what is left after a read failure or a cancellation
                } catch (_: IOException) {
                    // Only after an error that is already being reported.
                }
            }
        } catch (e: StorageException) {
            throw e
        } catch (e: IOException) {
            // A dropped connection: what was written stays for the next run.
            onProgress(currentLength(part))
            throw e
        }
        onProgress(done)
        return if (done == total) Outcome.Complete else Outcome.Again
    }

    private fun finish(targets: DownloadTargets, total: Long, onProgress: (Long) -> Unit): FileResult {
        val part = targets.part
        if (!part.isFile || part.length() != total) {
            part.delete()
            return FileResult.Failed(WRONG_SIZE)
        }
        FileOutputStream(part, true).use { it.fd.sync() }
        try {
            Files.move(part.toPath(), targets.final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), targets.final.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        onProgress(total)
        return FileResult.Done(targets.final)
    }

    /**
     * Fetches a small file (the cover) whole into [target], replacing it only when complete. Best effort:
     * false on any failure. [url] must be on [server] (as [source] gives it now); redirects are not followed.
     */
    suspend fun downloadSmall(source: DownloadSource, server: ServerConfig, url: String, target: File, maxBytes: Long = MAX_SMALL_BYTES): Boolean {
        if (!sameHost(server.baseUrl, url)) return false
        val temp = File(target.parentFile, target.name + PART_SUFFIX_SMALL)
        return try {
            val ok = source.authorizedSmall(server, url) { fetch ->
                // The credential only goes to the server's own address as it is now.
                if (!sameHost(fetch.server.baseUrl, url)) return@authorizedSmall false
                withHeaderTimeout { headersArrived -> client.prepareGet(url) { for ((name, value) in fetch.headers) header(name, value) }.execute { response ->
                    headersArrived()
                    if (response.status == HttpStatusCode.Unauthorized) throw HttpStatusException(response.status)
                    if (response.status != HttpStatusCode.OK) return@execute false
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    FileOutputStream(temp).use { out ->
                        while (true) {
                            val read = withTimeoutOrNull(stallMs) { channel.readAvailable(buffer, 0, buffer.size) } ?: return@execute false
                            if (read < 0) break
                            done += read
                            if (done > maxBytes) return@execute false
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                    done > 0L
                } } ?: false
            }
            if (ok) Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    /** Runs a disk operation; its IOException becomes a [StorageException], so it is never mistaken for the network. */
    private inline fun <T> disk(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw StorageException(storageMessage(e))
    }

    /**
     * Runs a request and gives up when its headers do not arrive within [stallMs] (null then). [block] calls
     * `headersArrived` as soon as the response starts; from there the body has its own stall check.
     */
    private suspend fun <T> withHeaderTimeout(block: suspend (headersArrived: () -> Unit) -> T): T? = coroutineScope {
        val headers = CompletableDeferred<Unit>()
        val work = async { block { headers.complete(Unit) } }
        work.invokeOnCompletion { headers.complete(Unit) }
        if (withTimeoutOrNull(stallMs) { headers.await() } == null) {
            work.cancelAndJoin()
            null
        } else {
            work.await()
        }
    }

    private fun currentLength(part: File) = if (part.isFile) part.length() else 0L

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_REDIRECTS = 5
        const val MAX_RESTARTS = 2
        /** New addresses one file may move to within one call (each after a failed connection). */
        const val MAX_SWITCHES = 2
        const val MAX_SMALL_BYTES = 20L * 1024 * 1024
        const val PART_SUFFIX_SMALL = ".part"

        const val WRONG_SIZE = "The downloaded file has the wrong size"
        const val UNSAFE_NAME = "The server sent an unsafe file name"
        const val NO_FOLDER = "Cannot create the download folder"
        const val MOVED = "The download moved to another address"
        const val FORBIDDEN = "The server refused the download"
        const val NOT_FOUND = "The file is no longer on the server"
        const val REFUSED = "The server refused the download"
    }
}

/** Writing to the phone's storage failed; [userMessage] is fixed text. */
internal class StorageException(val userMessage: String) : Exception(userMessage)

internal const val NOT_ENOUGH_SPACE = "Not enough space"
internal const val LIMIT_REACHED_TEXT = "The download storage limit is reached. Raise it in Settings or remove a download."
internal const val STORAGE_ERROR = "Cannot write the download to storage"

/** "Not enough space" for a full disk (ENOSPC), a general storage text otherwise. Never the exception's own text. */
internal fun storageMessage(e: IOException): String {
    val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    val full = "ENOSPC" in text || "no space left" in text.lowercase() || "not enough space" in text.lowercase()
    return if (full) NOT_ENOUGH_SPACE else STORAGE_ERROR
}

private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")

/** The start byte and the full length (null when the server sent `*`) of a `Content-Range` header. */
internal fun parseContentRange(header: String?): Pair<Long, Long?>? {
    val match = header?.trim()?.let { CONTENT_RANGE.matchEntire(it) } ?: return null
    return match.groupValues[1].toLong() to match.groupValues[3].toLongOrNull()
}

/** [location] against [base]: absolute, server-relative ("/x") or path-relative. Null when it cannot be read. */
internal fun resolve(base: String, location: String): String? {
    val loc = location.trim()
    return try {
        java.net.URI(base).resolve(loc).toString()
    } catch (_: Exception) {
        null
    }
}
