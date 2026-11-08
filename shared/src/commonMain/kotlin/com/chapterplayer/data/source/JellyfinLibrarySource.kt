package com.chapterplayer.data.source

import com.chapterplayer.data.auth.SignInRequiredException
import com.chapterplayer.data.diagnostics.ReadRefusedException
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.data.playback.PlaybackStartException
import com.chapterplayer.data.playback.PlaybackTrack
import com.chapterplayer.data.playback.UNSUPPORTED_AUDIO_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_LENGTH_UNKNOWN_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NO_AUDIO_TEXT
import com.chapterplayer.domain.jellyfin.jellyfinContainerSupported
import com.chapterplayer.domain.jellyfin.jellyfinMimeType
import com.chapterplayer.domain.jellyfin.jellyfinStreamUrl
import com.chapterplayer.domain.jellyfin.newPlaySessionId
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.SeriesRef
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.InstallId
import com.chapterplayer.data.remote.jellyfin.JellyfinApiService
import com.chapterplayer.data.remote.jellyfin.fetchAllAudiobooks
import com.chapterplayer.domain.diagnostics.ServerProgressReading
import com.chapterplayer.domain.jellyfin.JELLYFIN_NO_BOOKS_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_NOT_THIS_SERVER_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_OTHER_SERVER_TEXT
import com.chapterplayer.domain.jellyfin.JellyfinServerCheck
import com.chapterplayer.domain.jellyfin.checkJellyfinServer
import com.chapterplayer.domain.jellyfin.JELLYFIN_UNREADABLE_SYNC_TEXT
import com.chapterplayer.domain.jellyfin.JELLYFIN_UNREADABLE_VIEW_TEXT
import com.chapterplayer.domain.plex.classifyConnectionError
import kotlinx.io.IOException
import com.chapterplayer.domain.jellyfin.jellyfinBookViews
import com.chapterplayer.domain.jellyfin.jellyfinChapters
import com.chapterplayer.domain.jellyfin.jellyfinDeviceId
import com.chapterplayer.domain.jellyfin.jellyfinItemId
import com.chapterplayer.domain.jellyfin.jellyfinItemToBook
import com.chapterplayer.domain.jellyfin.jellyfinServerProgress
import com.chapterplayer.domain.jellyfin.jellyfinTicksToMs
import com.chapterplayer.domain.playback.ServerProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** A Jellyfin server does not take progress from the app yet (stage J1; J2 adds it). Fixed text, no details. */
class ProgressNotReportedYetException : Exception("This server does not take progress from the app yet")

/**
 * Reads a Jellyfin server: its Books views, their `AudioBook` items (one file per book; a multi-file folder passes
 * through as the separate items Jellyfin lists, never grouped), one book's details, and the user's play state. J1
 * writes nothing to the server: the session and progress calls keep the interface's no-op defaults.
 *
 * Every call sends the row's DeviceId ([jellyfinDeviceId] of [installId] and the server row id, the same as at sign-in)
 * and the token in the `Authorization` header. A 401 blanks the token ([markSignedOut]) and is a
 * [SignInRequiredException] (Jellyfin tokens have no refresh).
 *
 * The user data of the last snapshot of each server is kept in memory, so the sync's progress merge right after it
 * ([fetchAllProgress]) makes no further request.
 */
class JellyfinLibrarySource(
    private val api: JellyfinApiService,
    private val installId: InstallId,
    private val markSignedOut: suspend (ServerConfig) -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * The Jellyfin server id kept for a row at its first sign-in (null when none is known). Before a sync sends the token,
     * the address's anonymous public info must name that server, so a reused address never receives the token.
     */
    private val expectedServerId: suspend (rowId: String) -> String? = { null },
) : LibrarySource {

    override val type: ServerSource = ServerSource.JELLYFIN

    /**
     * A load the app opened: the item, Jellyfin's play session and media source ids (used by J2's reports), and the
     * last reported place. Read and written under [sessionsLock].
     */
    private class LiveSession(val itemId: String, val playSessionId: String, val mediaSourceId: String) {
        val lock = Mutex()
        var lastPositionMs: Long? = null
        var markedFinished = false
    }

    private val sessions = HashMap<String, LiveSession>()
    private var sessionCount = 0L
    private val sessionsLock = Mutex()

    /**
     * Opens the app's own session for [bookId] (Jellyfin has no PlaybackInfo call here): the item is read fresh with its
     * media sources and chapters, its first media source is the one file, streamed as the original (`static=true`) with
     * a new play session id, the token in the header only. It starts at the server's place unless that is none or
     * finished. A book with no media source, an unknown length or a container the app cannot play fails with a safe text.
     */
    override suspend fun startPlayback(server: ServerConfig, bookId: String): PlaybackSession = signedIn(server) {
        val itemId = itemIdOf(bookId)
        val item = api.getItem(server.baseUrl, deviceId(server), server.token, itemId)
        val media = item.mediaSources.firstOrNull() ?: throw PlaybackStartException(JELLYFIN_NO_AUDIO_TEXT)
        if (!jellyfinContainerSupported(media.container)) throw PlaybackStartException(UNSUPPORTED_AUDIO_TEXT)
        val durationMs = jellyfinTicksToMs(item.runTimeTicks).takeIf { it > 0L } ?: jellyfinTicksToMs(media.runTimeTicks)
        if (durationMs <= 0L) throw PlaybackStartException(JELLYFIN_LENGTH_UNKNOWN_TEXT)
        val mediaSourceId = media.id?.takeIf { it.isNotBlank() } ?: itemId
        val playSessionId = newPlaySessionId()
        val track = PlaybackTrack(
            index = 0,
            startMs = 0L,
            durationMs = durationMs,
            mimeType = jellyfinMimeType(media.container),
            url = jellyfinStreamUrl(server.baseUrl, itemId, mediaSourceId, playSessionId),
            fileId = itemId,
        )
        val reading = jellyfinServerProgress(item.userData, durationMs)
        val start = if (reading == null || reading.isFinished) 0L else reading.positionMs
        val sessionId = sessionsLock.withLock {
            sessionCount++
            "jf-$itemId-$sessionCount".also { sessions[it] = LiveSession(itemId, playSessionId, mediaSourceId) }
        }
        PlaybackSession(
            sessionId = sessionId,
            serverId = server.id,
            bookId = bookId,
            durationMs = durationMs,
            startPositionMs = start,
            tracks = listOf(track),
            chapters = jellyfinChapters(item.chapters, durationMs),
        )
    }

    /** J1 writes nothing to the server: closing only forgets the session (J2 sends the stop report). */
    override suspend fun closePlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long) {
        sessionsLock.withLock { sessions.remove(sessionId) }
    }

    /**
     * J1 cannot report yet, and says so instead of pretending it did: a live report that "succeeded" would let
     * `ProgressSyncer` delete the book's queued rows (its own and mirrors) although nothing reached the server. Failing,
     * it queues the position (one row per book) and keeps what was queued. J2 replaces this with the session reports.
     */
    override suspend fun syncPlayback(
        server: ServerConfig,
        sessionId: String,
        positionMs: Long,
        listenedMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ): Unit = throw ProgressNotReportedYetException()

    /**
     * J1 cannot deliver queued positions or marks yet: failing keeps the row queued (the flusher backs off and tries again;
     * a server reading newer than the row still drops it), so J2 delivers it with the UserData write.
     */
    override suspend fun pushProgress(server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean): Unit =
        throw ProgressNotReportedYetException()

    /** What the last snapshot of a server read: when, every book's progress and every book's length. */
    private class SnapshotCache(val atMs: Long, val progress: Map<String, ServerProgress>, val lengths: Map<String, Long>)

    private val cacheLock = Mutex()
    private val cache = HashMap<String, SnapshotCache>()

    private fun deviceId(server: ServerConfig) = jellyfinDeviceId(installId.value, server.id)

    /** Runs [call]; a 401 blanks the server's token ([markSignedOut]) and is a [SignInRequiredException]. */
    private suspend fun <T> signedIn(server: ServerConfig, call: suspend () -> T): T =
        try {
            call()
        } catch (e: HttpStatusException) {
            if (e.status.value != 401) throw e
            try {
                withContext(NonCancellable) { markSignedOut(server) }
            } catch (_: Exception) {
                // Best effort: the caller still learns that the sign-in is gone.
            }
            throw SignInRequiredException(server.name)
        }

    override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> = fetchSnapshot(server).books

    override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = signedIn(server) { bookViews(server) }

    /** Jellyfin collections (BoxSets) are not read yet. */
    override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()

    /**
     * One sync: the views are read once; each Books view the user did not turn off is read page by page. A view that
     * fails is reported in [SourceSnapshot.failedLibraries] (its cached books stay); only when every view fails does the
     * sync fail with that error. A server with no Books view at all says so.
     */
    override suspend fun fetchSnapshot(server: ServerConfig): SourceSnapshot = signedIn(server) {
        checkIdentity(server)
        val views = try {
            bookViews(server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Status and connection errors as they are (a 401 still signs out); an unreadable answer in fixed words.
            throw syncFailure(e)
        }
        if (views.isEmpty()) error(JELLYFIN_NO_BOOKS_TEXT)
        val read = views.filter { it.id !in server.excludedLibraryIds }
            .filter { server.libraryId.isNullOrBlank() || it.id == server.libraryId }
        val books = mutableListOf<AudioBook>()
        val progress = HashMap<String, ServerProgress>()
        val failed = LinkedHashMap<String, String>()
        var firstError: Exception? = null
        for (view in read) {
            try {
                for (item in fetchAllAudiobooks(api, server.baseUrl, deviceId(server), server.token, view.id)) {
                    val book = jellyfinItemToBook(item, server.baseUrl, server.id, view.id) ?: continue
                    books += book
                    jellyfinServerProgress(item.userData, book.durationMs)?.let { progress[book.id] = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refused token is the whole server's, not one view's.
                if (e is HttpStatusException && e.status.value == 401) throw e
                // Fixed words, never the server's own text (a decode error quotes the answer).
                failed[view.id] = "${view.name}: ${viewFailureText(e)}"
                if (firstError == null) firstError = e
            }
        }
        firstError?.let { if (failed.size == read.size) throw syncFailure(it) }
        cacheLock.withLock {
            cache[server.id] = SnapshotCache(nowMs(), progress, books.associate { it.id to it.durationMs })
        }
        SourceSnapshot(books = books, libraries = views, collections = emptyList(), failedLibraries = failed)
    }

    /** One book's chapters, description, genres, year, series and length (`GET /Items/{Id}` with the detail fields). */
    override suspend fun fetchBookDetails(server: ServerConfig, bookId: String): BookDetails = signedIn(server) {
        val item = api.getItem(server.baseUrl, deviceId(server), server.token, itemIdOf(bookId))
        val durationMs = jellyfinTicksToMs(item.runTimeTicks)
        BookDetails(
            chapters = jellyfinChapters(item.chapters, durationMs),
            description = item.overview?.takeIf { it.isNotBlank() },
            genres = item.genres.filter { it.isNotBlank() },
            publishedYear = item.productionYear?.toString(),
            series = item.seriesName?.takeIf { it.isNotBlank() }?.let { listOf(SeriesRef(it, null)) }.orEmpty(),
            durationMs = durationMs.takeIf { it > 0L },
        )
    }

    /** `GET /UserItems/{Id}/UserData`; null when the user has no progress (0 and not played). */
    override suspend fun fetchProgress(server: ServerConfig, bookId: String): ServerProgress? = signedIn(server) {
        readProgress(server, bookId)
    }

    override suspend fun fetchProgressReading(server: ServerConfig, bookId: String): ServerProgressReading =
        // Not through signedIn: a refused token must not blank the server's sign-in from a read-only page.
        try {
            if (server.token.isBlank()) throw SignInRequiredException(server.name)
            ServerProgressReading(readProgress(server, bookId))
        } catch (e: HttpStatusException) {
            if (e.status.value == 401) throw ReadRefusedException()
            throw e
        }

    /**
     * Every book's progress: the user data of this server's last snapshot when it is under [SNAPSHOT_REUSE_MS] old (the
     * sync's merge right after it costs nothing), else a fresh read of every view.
     */
    override suspend fun fetchAllProgress(server: ServerConfig): Map<String, ServerProgress> {
        val recent = cacheLock.withLock { cache[server.id]?.takeIf { nowMs() - it.atMs < SNAPSHOT_REUSE_MS } }
        if (recent != null) return recent.progress
        fetchSnapshot(server)
        return cacheLock.withLock { cache[server.id]?.progress.orEmpty() }
    }

    private suspend fun readProgress(server: ServerConfig, bookId: String): ServerProgress? {
        val itemId = itemIdOf(bookId)
        val data = api.getUserData(server.baseUrl, deviceId(server), server.token, itemId)
        // The length is needed to place "finished"; a book not synced yet reads it from the item.
        val known = cacheLock.withLock { cache[server.id]?.lengths?.get(bookId) }
        val durationMs = known ?: jellyfinTicksToMs(api.getItem(server.baseUrl, deviceId(server), server.token, itemId).runTimeTicks)
        return jellyfinServerProgress(data, durationMs)
    }

    /**
     * The address must still belong to the server signed in to, before any token is sent: its public info (no token)
     * must be Jellyfin's and name the server id kept at sign-in. Only an address that cannot be reached at all (no
     * connection, a timeout, a certificate problem) is let through, because the token calls cannot reach it either; any
     * other answer (a web page, an error status, another app's JSON, Jellyfin without the id) stops the sync with
     * [JELLYFIN_NOT_THIS_SERVER_TEXT], and another Jellyfin server with [JELLYFIN_OTHER_SERVER_TEXT]. A row with no id
     * kept (only rows saved before the id was kept) is not checked. Calls outside a sync (details, progress, playback)
     * are not checked.
     */
    private suspend fun checkIdentity(server: ServerConfig) {
        val expected = expectedServerId(server.id)?.takeIf { it.isNotBlank() } ?: return
        val info = try {
            api.getPublicInfo(server.baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Before IOException: HttpStatusException is one, and an error status is an answer.
            if (e is IOException && e !is HttpStatusException) return
            throw IllegalStateException(JELLYFIN_NOT_THIS_SERVER_TEXT)
        }
        when (val check = checkJellyfinServer(info)) {
            is JellyfinServerCheck.Ok -> check(check.serverId.equals(expected, ignoreCase = true)) { JELLYFIN_OTHER_SERVER_TEXT }
            else -> throw IllegalStateException(JELLYFIN_NOT_THIS_SERVER_TEXT)
        }
    }

    private suspend fun bookViews(server: ServerConfig): List<LibraryInfo> =
        jellyfinBookViews(api.getUserViews(server.baseUrl, deviceId(server), server.token).items, server.id)

    private fun itemIdOf(bookId: String): String =
        requireNotNull(jellyfinItemId(bookId)) { "Not a Jellyfin book" }

    private companion object {
        /** A view's failure in fixed words: the status, the kind of connection problem, or an unreadable answer. */
        fun viewFailureText(e: Exception): String = when (e) {
            // Before IOException: HttpStatusException is one.
            is HttpStatusException -> "the server answered with HTTP ${e.status.value}"
            is IOException -> classifyConnectionError(e).text
            else -> JELLYFIN_UNREADABLE_VIEW_TEXT
        }

        /** What a sync whose every view failed throws: status and connection errors as they are, anything else in fixed words. */
        fun syncFailure(e: Exception): Exception =
            if (e is IOException) e else IllegalStateException(JELLYFIN_UNREADABLE_SYNC_TEXT)

        /** How long a snapshot's user data stands in for a fresh read of every book's progress. */
        const val SNAPSHOT_REUSE_MS = 60_000L
    }
}
