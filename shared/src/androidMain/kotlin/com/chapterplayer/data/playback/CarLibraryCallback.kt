package com.chapterplayer.data.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.chapterplayer.data.DownloadStates
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.car.CarAccess
import com.chapterplayer.domain.car.CarConnectionTracker
import com.chapterplayer.domain.car.CarData
import com.chapterplayer.domain.car.CarFailure
import com.chapterplayer.domain.car.CarNowPlaying
import com.chapterplayer.domain.car.CarPlay
import com.chapterplayer.domain.car.CarRequest
import com.chapterplayer.domain.car.carAccess
import com.chapterplayer.domain.car.carChildrenFor
import com.chapterplayer.domain.car.carConnectResume
import com.chapterplayer.domain.car.carFailureMessage
import com.chapterplayer.domain.car.carItemFor
import com.chapterplayer.domain.car.carPage
import com.chapterplayer.domain.car.carRootItem
import com.chapterplayer.domain.car.carSearchResults
import com.chapterplayer.domain.car.parseCarRequest
import com.chapterplayer.domain.car.playerHoldsBook
import com.chapterplayer.domain.car.resolveCarRequest
import com.chapterplayer.domain.car.resumeLast
import com.chapterplayer.domain.playback.isOwnTrackRequest
import com.chapterplayer.domain.playback.locate
import com.chapterplayer.ui.browse.observeBrowseItems
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A car request that could not be played; the message is safe to show and to speak. */
class CarRequestException(message: String) : Exception(message)

/**
 * The playback service's answers to Android Auto, the Assistant and the system, and the gate for every
 * controller. Who may do what comes from [carAccess] (the framework-checked package name); the tree,
 * the request resolution and the resume rule are pure code in `domain/car`. Books are always started
 * through [starter], the same load path as the phone; the car's request is then answered with the
 * player's current items so they are not loaded a second time (see [expectAnswer]).
 *
 * Every callback runs on the main thread, like [scope].
 *
 * @param player the session's player, to read what it holds.
 * @param expectAnswer told the exact items an answer hands back, so the player does not set them again.
 */
@UnstableApi
class CarLibraryCallback(
    private val ownPackage: String,
    private val player: Player,
    private val expectAnswer: (List<MediaItem>) -> Unit,
    private val books: BookRepository,
    private val downloads: DownloadStates,
    private val connectivity: ConnectivityMonitor,
    private val settings: AppSettings,
    private val starter: PlaybackStarter,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : MediaLibrarySession.Callback {

    private val carConnections = CarConnectionTracker<MediaSession.ControllerInfo>()

    private fun access(controller: MediaSession.ControllerInfo): CarAccess =
        carAccess(controller.packageName, ownPackage, controller.isPackageNameVerified)

    // -- Connecting --------------------------------------------------------------------------

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): ConnectionResult {
        val base = super.onConnect(session, controller)
        val access = access(controller)
        if (access == CarAccess.FULL) return base
        // Nobody but this app edits the playlist; only a car may ask for a book (which we then load).
        val playerCommands = base.availablePlayerCommands.buildUpon()
            .removeAll(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_PLAYLIST_METADATA)
            .apply { if (!access.canStartBooks) remove(Player.COMMAND_SET_MEDIA_ITEM) }
            .build()
        val sessionCommands = if (access.canBrowse) base.availableSessionCommands else ConnectionResult.DEFAULT_SESSION_COMMANDS
        return ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .build()
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (carConnections.connected(controller, controller.packageName, controller.isPackageNameVerified)) {
            scope.launch { resumeOnConnect(controller) }
        }
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        carConnections.disconnected(controller)
    }

    // Counts books the car asked for; a resume on connect gives way to the listener's own request.
    private var carStarts = 0

    private suspend fun resumeOnConnect(controller: MediaSession.ControllerInfo) {
        try {
            val startsBefore = carStarts
            val enabled = settings.resumeOnCarConnect.first()
            val snap = snapshot()
            val busy = playback.state.value.bookId != null || player.isPlaying || player.mediaItemCount > 0
            val book = carConnectResume(
                enabled, controller.packageName, controller.isPackageNameVerified, busy, snap.data.items, snap.online, now(),
            ) ?: return
            val busyNow = playback.state.value.bookId != null || player.isPlaying || player.mediaItemCount > 0
            if (carStarts != startsBefore || busyNow) return
            starter.start(book)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the car shows the library; the listener can start a book by hand.
        }
    }

    // -- Browsing ----------------------------------------------------------------------------

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!access(browser).canBrowse) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return Futures.immediateFuture(LibraryResult.ofItem(carMediaSpec(carRootItem()).toMediaItem(), params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!access(browser).canBrowse) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return future {
            val snap = snapshot()
            val rows = carChildrenFor(parentId, snap.data, snap.online, nowPlaying(snap.data))?.let { carPage(it, page, pageSize) }
            if (rows == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            else LibraryResult.ofItemList(rows.map { carMediaSpec(it).toMediaItem() }, params)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (!access(browser).canBrowse) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return future {
            val snap = snapshot()
            val item = carItemFor(mediaId, snap.data, snap.online, nowPlaying(snap.data))
            if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(carMediaSpec(item).toMediaItem(), null)
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        if (!access(browser).canBrowse) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return future {
            val snap = snapshot()
            session.notifySearchResultChanged(browser, query, carSearchResults(query, snap.data, snap.online).size, params)
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!access(browser).canBrowse) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
        return future {
            val snap = snapshot()
            val rows = carPage(carSearchResults(query, snap.data, snap.online), page, pageSize)
            if (rows == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            else LibraryResult.ofItemList(rows.map { carMediaSpec(it).toMediaItem() }, params)
        }
    }

    // -- Playing -----------------------------------------------------------------------------

    // The service is exported: only this app may queue items, and only our own tracks.
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        if (access(controller).canSetOwnTracks && mediaItems.all { isOwnTrackRequest(controller.packageName, ownPackage, it.mediaId) }) {
            Futures.immediateFuture(mediaItems)
        } else {
            Futures.immediateFailedFuture(fail(mediaSession, controller, CarFailure.REFUSED))
        }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        val access = access(controller)
        // This app: the track rule in onAddMediaItems decides.
        if (access.canSetOwnTracks) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        if (!access.canStartBooks || mediaItems.size != 1) {
            return Futures.immediateFailedFuture(fail(mediaSession, controller, CarFailure.REFUSED))
        }
        val item = mediaItems[0]
        val request = parseCarRequest(
            mediaId = item.mediaId,
            searchQuery = item.requestMetadata.searchQuery,
            hasUri = item.localConfiguration != null || item.requestMetadata.mediaUri != null,
        )
        return future { play(mediaSession, controller, request) }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = future {
        val snap = snapshot()
        when (val r = resumeLast(snap.data.items, snap.online, nowPlaying(snap.data))) {
            is CarPlay.Start ->
                if (isForPlayback) startAndAnswer(mediaSession, controller, r.book)
                // Only for the system's "resume" card: describe the book, load nothing.
                else MediaItemsWithStartPosition(listOf(preview(r.book)), 0, C.TIME_UNSET)
            is CarPlay.Fail -> throw CarRequestException(carFailureMessage(r.reason))
            is CarPlay.SeekChapter -> throw CarRequestException(carFailureMessage(CarFailure.NOTHING_TO_RESUME))
        }
    }

    private suspend fun play(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        request: CarRequest,
    ): MediaItemsWithStartPosition {
        val snap = snapshot()
        return when (val r = resolveCarRequest(request, snap.data.items, snap.online, nowPlaying(snap.data))) {
            is CarPlay.Start -> startAndAnswer(session, controller, r.book)
            is CarPlay.SeekChapter -> chapterAnswer(session, controller, r.index)
            is CarPlay.Fail -> throw fail(session, controller, r.reason)
        }
    }

    // One load through the starter (server session, saved speed, progress saver), then the player's own items.
    private suspend fun startAndAnswer(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        book: AudioBook,
    ): MediaItemsWithStartPosition {
        carStarts++
        starter.start(book).onFailure {
            throw report(session, controller, SessionError.ERROR_IO, it.message ?: PlaybackStartException.DEFAULT_MESSAGE)
        }
        // The starter's controller hands the items to the player asynchronously: wait until it holds them.
        val holds = withTimeoutOrNull(PLAYER_WAIT_MS) {
            while (!playerHoldsBook(mediaIds(), book.id)) delay(PLAYER_POLL_MS)
            true
        } ?: false
        if (!holds) throw report(session, controller, SessionError.ERROR_UNKNOWN, PlaybackStartException.DEFAULT_MESSAGE)
        return answer(player.currentMediaItemIndex, player.currentPosition)
    }

    // A chapter of the loaded book: the answer starts at the chapter, which the player turns into a seek.
    private fun chapterAnswer(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        index: Int,
    ): MediaItemsWithStartPosition {
        val chapter = playback.state.value.chapters.getOrNull(index)
        val at = chapter?.let { c -> player.bookSpans()?.let { locate(it, c.startMs) } }
            ?: throw fail(session, controller, CarFailure.NO_CHAPTER)
        return answer(at.trackIndex, at.offsetMs)
    }

    private fun answer(index: Int, positionMs: Long): MediaItemsWithStartPosition {
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        expectAnswer(items)
        return MediaItemsWithStartPosition(items, index, positionMs)
    }

    private fun preview(book: AudioBook): MediaItem =
        MediaItem.Builder()
            .setMediaId(book.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build()
            )
            .build()

    // -- Helpers -----------------------------------------------------------------------------

    private class Snapshot(val data: CarData, val online: Boolean)

    // One consistent view of the visible books (the same set Home and Library show) per call.
    private suspend fun snapshot(): Snapshot = withContext(Dispatchers.Default) {
        val items = observeBrowseItems(books, downloads).first()
        val collections = books.observeCollections().first()
        val online = connectivity.isOnline.first()
        Snapshot(CarData(items, collections), online)
    }

    private fun mediaIds(): List<String> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    // The loaded book's chapters, only while the player really holds that book.
    private fun nowPlaying(data: CarData): CarNowPlaying? {
        val state = playback.state.value
        val id = state.bookId ?: return null
        if (!playerHoldsBook(mediaIds(), id)) return null
        val title = data.items.firstOrNull { it.book.id == id }?.book?.title.orEmpty()
        return CarNowPlaying(id, title, state.chapters)
    }

    private fun fail(session: MediaSession, controller: MediaSession.ControllerInfo, reason: CarFailure): Exception =
        report(session, controller, carErrorCode(reason), carFailureMessage(reason))

    // Tells the controller why (Android Auto shows it, the Assistant says it); the message holds no secrets.
    private fun report(session: MediaSession, controller: MediaSession.ControllerInfo, code: Int, message: String): Exception {
        try {
            session.sendError(controller, SessionError(code, message))
        } catch (_: Exception) {
            // The controller is gone; the failed future is answer enough.
        }
        return CarRequestException(message)
    }

    // Runs [block] in the service scope; cancelling the future cancels the work.
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                result.set(block())
            } catch (e: CancellationException) {
                result.cancel(false)
                throw e
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private companion object {
        const val PLAYER_WAIT_MS = 5_000L
        const val PLAYER_POLL_MS = 50L
    }
}
