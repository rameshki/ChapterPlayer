package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.BookDetails
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.dto.PlexSectionDto
import com.chapterplayer.data.remote.plex.fetchAllPlexItems
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto
import com.chapterplayer.data.playback.PlaybackSession
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.plex.PLEX_AUDIO_MISSING_TEXT
import com.chapterplayer.domain.plex.PlexBookContent
import com.chapterplayer.domain.plex.PlexTrack
import com.chapterplayer.domain.plex.plexPlaybackTracks
import com.chapterplayer.domain.plex.plexCompletedTracks
import com.chapterplayer.domain.plex.plexProgressPlan
import com.chapterplayer.domain.plex.plexServerProgress
import com.chapterplayer.domain.plex.plexTimelinePoint
import com.chapterplayer.domain.plex.PlexEntryKind
import com.chapterplayer.domain.plex.isPlexTrack
import com.chapterplayer.domain.plex.plexBookContent
import com.chapterplayer.domain.plex.plexRatingKey
import com.chapterplayer.domain.plex.plexAlbumToBook
import com.chapterplayer.domain.plex.plexEntryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** How many track details a book's details read at once. */
internal const val PLEX_DETAIL_CONCURRENCY = 4

private const val TIMELINE_PLAYING = "playing"
private const val TIMELINE_STOPPED = "stopped"

/**
 * Lists the books on a Plex Media Server. In a Plex music library each audiobook is an album (the author is the
 * album's artist); its audio is the album's tracks and its chapters come from each track's own metadata.
 * The token only ever travels in the `X-Plex-Token` header, never in an address the app stores.
 */
class PlexLibrarySource(private val api: PlexApiService) : LibrarySource {

    override val type: ServerSource = ServerSource.PLEX

    private val sessionCount = AtomicLong()

    /**
     * A session this app opened (Plex has no sessions of its own): the book's tracks, the track of the last report and
     * the tracks already scrobbled in it. One report at a time per session ([lock]).
     */
    private class LiveSession(val tracks: List<PlexTrack>) {
        val lock = Mutex()
        var lastIndex: Int? = null
        val scrobbled = HashSet<Int>()
    }

    /** The sessions this app opened, by session id; a closed one is forgotten. */
    private val sessions = ConcurrentHashMap<String, LiveSession>()

    /**
     * Plex has no playback session to open: the app makes one from the book's tracks, read fresh ([fetchContent]).
     * One item per track on one timeline; the addresses are the parts' paths under the server address, with no token
     * (the player's data source adds the `X-Plex-Token` header). Listening resumes where the server's track states say
     * ([plexServerProgress]; a finished book starts over). Throws when a track has no file or a call fails.
     */
    override suspend fun startPlayback(server: ServerConfig, bookId: String): PlaybackSession {
        val content = fetchContent(server, bookId)
        val tracks = plexPlaybackTracks(server.baseUrl, content.tracks) ?: error(PLEX_AUDIO_MISSING_TEXT)
        val progress = plexServerProgress(content.tracks)
        val sessionId = "plex-${plexRatingKey(bookId)}-${sessionCount.incrementAndGet()}"
        sessions[sessionId] = LiveSession(content.tracks)
        return PlaybackSession(
            sessionId = sessionId,
            serverId = server.id,
            bookId = bookId,
            durationMs = content.durationMs,
            startPositionMs = if (progress == null || progress.isFinished) 0L else progress.positionMs,
            tracks = tracks,
            chapters = content.chapters,
        )
    }

    override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> =
        sectionIds(server, if (server.libraryId.isNullOrBlank()) musicSections(server) else null)
            .flatMap { sectionId -> booksIn(server, sectionId) }

    /**
     * One sync: the section list is read once and shared by the books and the libraries. A section that fails is
     * reported in [SourceSnapshot.failedLibraries] while the others are stored; only when every section fails does
     * the sync fail as a whole (with that error), so the cache stays as it was.
     */
    override suspend fun fetchSnapshot(server: ServerConfig): SourceSnapshot {
        // A configured section needs no list for its books; the list only names it, so its failure is not fatal then.
        val sections = if (server.libraryId.isNullOrBlank()) musicSections(server) else fetchOrNull { musicSections(server) }
        val ids = sectionIds(server, sections)
        val books = mutableListOf<AudioBook>()
        val failed = LinkedHashMap<String, String>()
        var firstError: Exception? = null
        for (sectionId in ids) {
            try {
                books += booksIn(server, sectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val name = sections?.firstOrNull { it.key == sectionId }?.title ?: sectionId
                failed[sectionId] = "$name: ${e.message ?: "Unknown error"}"
                if (firstError == null) firstError = e
            }
        }
        if (firstError != null && failed.size == ids.size) throw firstError
        return SourceSnapshot(
            books = books,
            libraries = sections?.let { librariesOf(server, it) },
            collections = emptyList(),
            failedLibraries = failed
        )
    }

    /**
     * A configured section is read unless the user turned it off; otherwise every music section in [listed]
     * (audiobooks live in music libraries in Plex) except the ones the user turned off.
     */
    private fun sectionIds(server: ServerConfig, listed: List<PlexSectionDto>?): List<String> =
        if (!server.libraryId.isNullOrBlank()) {
            listOf(server.libraryId).filter { it !in server.excludedLibraryIds }
        } else {
            requireNotNull(listed) { "The server's section list is missing" }.map { it.key }.filter { it !in server.excludedLibraryIds }
        }

    /** The section's albums as books; artists (a server that ignored the album filter) are opened for their albums. */
    private suspend fun booksIn(server: ServerConfig, sectionId: String): List<AudioBook> {
        val entries = fetchAllPlexItems { start, size -> api.getSectionAlbums(server.baseUrl, server.token, sectionId, start, size) }
        val albums = LinkedHashMap<String, PlexMetadataDto>()
        for (entry in entries) {
            when (plexEntryKind(entry)) {
                PlexEntryKind.ALBUM -> albums.putIfAbsent(entry.ratingKey, entry)
                PlexEntryKind.ARTIST -> api.getChildren(server.baseUrl, server.token, entry.ratingKey)
                    .mediaContainer.metadata.orEmpty()
                    .filter { plexEntryKind(it) == PlexEntryKind.ALBUM }
                    // An album listed under its artist may leave out the artist's name; the artist's title is the author.
                    .forEach { albums.putIfAbsent(it.ratingKey, if (it.author.isNullOrBlank()) it.copy(author = entry.title) else it) }
                PlexEntryKind.OTHER -> Unit
            }
        }
        return albums.values.map { plexAlbumToBook(it, server.baseUrl, server.id, sectionId) }
    }

    /**
     * Asked when a book is opened: the album's own metadata (description, year, genres), then its tracks and their chapters
     * ([fetchContent]). The chapters span all the tracks; the length is the sum of the tracks.
     */
    override suspend fun fetchBookDetails(server: ServerConfig, bookId: String): BookDetails {
        val album = api.getMetadata(server.baseUrl, server.token, plexRatingKey(bookId)).mediaContainer.metadata?.firstOrNull()
        val content = fetchContent(server, bookId)
        return BookDetails(
            chapters = content.chapters,
            description = album?.summary?.takeIf { it.isNotBlank() },
            genres = album?.genres.orEmpty().map { it.tag }.filter { it.isNotBlank() },
            publishedYear = album?.year?.toString(),
            series = emptyList(),
            downloadSizeBytes = content.sizeBytes,
            durationMs = content.durationMs.takeIf { it > 0L }
        )
    }

    /**
     * The book's tracks in play order and its chapters. A track list never carries chapters, so the first track's own
     * metadata is read with them; when it has some (or could not be read), every other track is read too, at most
     * [PLEX_DETAIL_CONCURRENCY] at a time (each file of a multi-file book may carry its own). When the first has none,
     * the others are taken to have none either (one chapter per track), which saves a request per file for the common
     * one-file-per-chapter book. A track whose metadata cannot be read is one chapter, so one bad file does not fail the
     * book; the track list itself must come. What playback needs in step 2 is in the result.
     */
    suspend fun fetchContent(server: ServerConfig, bookId: String): PlexBookContent {
        val tracks = api.getChildren(server.baseUrl, server.token, plexRatingKey(bookId)).mediaContainer.metadata.orEmpty()
            .filter(::isPlexTrack)
        val first = tracks.firstOrNull() ?: return plexBookContent(emptyList(), emptyMap())
        val details = LinkedHashMap<String, PlexMetadataDto>()
        val firstRead = trackDetailOrNull(server, first.ratingKey)
        firstRead?.detail?.let { details[first.ratingKey] = it }
        if (firstRead == null || !firstRead.detail?.chapters.isNullOrEmpty()) {
            val gate = Semaphore(PLEX_DETAIL_CONCURRENCY)
            val rest = coroutineScope {
                tracks.drop(1).map { track ->
                    async { track.ratingKey to gate.withPermit { trackDetailOrNull(server, track.ratingKey)?.detail } }
                }.awaitAll()
            }
            rest.forEach { (key, detail) -> if (detail != null) details[key] = detail }
        }
        return plexBookContent(tracks, details)
    }

    /**
     * Reports the session's position to Plex: a timeline for the track that holds it (`playing`, or `stopped` when
     * playback paused or stopped), then a scrobble for each track this report finishes ([plexCompletedTracks]; each
     * at most once per session). An unknown session (the app was restarted) throws, so the position is queued and
     * sent through [pushProgress]. Throws when a call fails.
     */
    override suspend fun syncPlayback(
        server: ServerConfig,
        sessionId: String,
        positionMs: Long,
        listenedMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        val live = sessions[sessionId] ?: error("The playback session is over")
        report(server, live, positionMs, if (isPlaying) TIMELINE_PLAYING else TIMELINE_STOPPED)
    }

    /** Ends the session: a stopped timeline at [positionMs] (and the scrobbles it finishes); the session is forgotten. */
    override suspend fun closePlayback(server: ServerConfig, sessionId: String, positionMs: Long, listenedMs: Long) {
        val live = sessions.remove(sessionId) ?: return
        report(server, live, positionMs, TIMELINE_STOPPED)
    }

    private suspend fun report(server: ServerConfig, live: LiveSession, positionMs: Long, state: String) = live.lock.withLock {
        val point = plexTimelinePoint(live.tracks, positionMs) ?: return@withLock
        api.timeline(server.baseUrl, server.token, point.ratingKey, state, point.offsetMs, point.durationMs)
        for (index in plexCompletedTracks(live.tracks, live.lastIndex, point)) {
            if (index in live.scrobbled) continue
            api.scrobble(server.baseUrl, server.token, live.tracks[index].ratingKey)
            live.scrobbled += index
        }
        live.lastIndex = point.trackIndex
    }

    /**
     * Sets the book to [positionMs] without a session (a queued position, or Mark as finished / not finished): the
     * tracks are read fresh for their state and [plexProgressPlan] says which to unscrobble, where to set the offset
     * and which to scrobble. Finished scrobbles every track. Throws when a call fails (the queue tries again later).
     */
    override suspend fun pushProgress(server: ServerConfig, bookId: String, positionMs: Long, durationMs: Long, isFinished: Boolean) {
        val plan = plexProgressPlan(tracksOf(server, bookId), positionMs, isFinished)
        for (key in plan.unscrobble) api.unscrobble(server.baseUrl, server.token, key)
        plan.point?.let { api.timeline(server.baseUrl, server.token, it.ratingKey, TIMELINE_STOPPED, it.offsetMs, it.durationMs) }
        for (key in plan.scrobble) api.scrobble(server.baseUrl, server.token, key)
    }

    /**
     * The server's progress for the book, from its tracks' listening state ([plexServerProgress]): one call, the track
     * list carries each track's offset, play count and last play time. Null when no track was ever played.
     */
    override suspend fun fetchProgress(server: ServerConfig, bookId: String): ServerProgress? =
        plexServerProgress(tracksOf(server, bookId))

    /** The book's tracks in play order with their listening state, from the track list alone (no chapters). */
    private suspend fun tracksOf(server: ServerConfig, bookId: String): List<PlexTrack> =
        plexBookContent(api.getChildren(server.baseUrl, server.token, plexRatingKey(bookId)).mediaContainer.metadata.orEmpty(), emptyMap())
            .tracks

    /** What reading one track gave: [detail] is null when the server sent no entry. */
    private class TrackRead(val detail: PlexMetadataDto?)

    /** One track's metadata with its chapters; null when the call failed (cancellation still propagates). */
    private suspend fun trackDetailOrNull(server: ServerConfig, ratingKey: String): TrackRead? =
        try {
            TrackRead(api.getMetadata(server.baseUrl, server.token, ratingKey, includeChapters = true).mediaContainer.metadata?.firstOrNull())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = librariesOf(server, musicSections(server))

    private fun librariesOf(server: ServerConfig, sections: List<PlexSectionDto>): List<LibraryInfo> {
        if (!server.libraryId.isNullOrBlank()) {
            val name = sections.firstOrNull { it.key == server.libraryId }?.title ?: server.libraryId
            return listOf(LibraryInfo(server.id, server.libraryId, name))
        }
        return sections.map { LibraryInfo(server.id, it.key, it.title) }
    }

    /** Plex has no collections in the app yet. */
    override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()

    /** "artist" == music/audiobook section in Plex. */
    private suspend fun musicSections(server: ServerConfig): List<PlexSectionDto> =
        api.getLibrarySections(baseUrl = server.baseUrl, token = server.token)
            .mediaContainer
            .directories
            ?.filter { it.type == "artist" }
            ?: emptyList()
}
