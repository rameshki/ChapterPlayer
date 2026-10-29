package com.chapterplayer.data.playback

import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.downloads.LocalFile
import com.chapterplayer.data.merge.ProgressMirrorPeers
import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.playback.LocalCopy
import com.chapterplayer.domain.playback.MirrorPeer
import com.chapterplayer.domain.playback.PlaybackSource
import com.chapterplayer.domain.playback.ServerProgress
import com.chapterplayer.domain.playback.choosePlaybackSource
import com.chapterplayer.domain.playback.chooseStartPosition
import com.chapterplayer.domain.playback.newestAcrossCopies
import com.chapterplayer.domain.playback.progressOnCopy
import com.chapterplayer.domain.playback.startPositionFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts a book playing: works out where it begins, loads it, applies the saved speed and plays.
 * Every screen that starts a book (details, Home) goes through here.
 */
class PlaybackStarter(
    private val controller: PlaybackController,
    private val books: BookRepository,
    private val settings: AppSettings,
    private val connectivity: ConnectivityMonitor,
    private val localFiles: LocalBookFiles,
    /** The other copies of a merged book (none with mirroring off): their progress counts at the start too. */
    private val peers: ProgressMirrorPeers = ProgressMirrorPeers.None,
) {

    /**
     * Starts [book] at [atMs] when given. Otherwise it starts where this phone left off
     * (see [startPositionFor]); a book that is already loaded in the player (and not in an error) is never reloaded: it plays on
     * from where it is (nothing happens when it is already playing). A failure carries a message that is safe to show; cancellation is rethrown.
     * The saved speed is a nicety: if it cannot be read or applied the book still plays.
     *
     * A downloaded book plays from its files (see [choosePlaybackSource]); online it still gets a server session.
     * [ignoreDownloadedOnly] lets a book that is not downloaded stream even with "Downloaded only" on (the car
     * does not follow that setting).
     */
    suspend fun start(book: AudioBook, atMs: Long? = null, ignoreDownloadedOnly: Boolean = false): Result<Unit> = try {
        if (atMs == null && controller.state.value.let { it.bookId == book.id && it.error == null }) {
            // Already loaded: carry on from the live position. A reload would restart from the last saved one.
            continueLoaded()
            return Result.success(Unit)
        }
        val online = isOnline()
        val files = downloadedFiles(book.id)
        val copy = if (files != null) LocalCopy.COMPLETE else LocalCopy.NONE
        val downloadedOnly = !ignoreDownloadedOnly && downloadedOnly()
        val origin = when (val source = choosePlaybackSource(book.source, copy, online, downloadedOnly)) {
            PlaybackSource.Stream -> AudioOrigin.Server
            PlaybackSource.Local -> AudioOrigin.Phone(files!!, openSession = online)
            is PlaybackSource.Unavailable -> throw PlaybackStartException(source.reason)
        }
        val startMs = atMs ?: startFromProgress(book, online)
        controller.load(book, startMs, origin)
        applySavedSpeed()
        controller.play()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlaybackStartException) {
        Result.failure(PlaybackStartException(e.message ?: PlaybackStartException.DEFAULT_MESSAGE))
    } catch (_: Exception) {
        Result.failure(PlaybackStartException(PlaybackStartException.DEFAULT_MESSAGE))
    }

    /**
     * Where a book begins when nobody said: the newer of this phone's progress and the server's (see
     * [chooseStartPosition]); with neither, [startPositionFor] lets the session's own position win. The servers are asked
     * only while online and for at most [SERVER_WAIT_MS] (all at once); a failure or a timeout means this phone's progress alone.
     *
     * A merged book also weighs its other copies: their own rows here (not mirrored ones, which only repeat a copy) and,
     * online, their servers' progress, each mapped by fraction ([progressOnCopy]); the newest of all wins
     * ([newestAcrossCopies]), so listening on another device against the other server is found.
     */
    private suspend fun startFromProgress(book: AudioBook, online: Boolean): Long {
        val local = books.observeProgress(book.id).first()
        val copies = peersOf(book.id)
        val (server, fromServers) = if (online) {
            coroutineScope {
                val own = async { serverProgress(book.id) }
                val others = copies.map { peer -> async { serverProgress(peer.bookId)?.withLengthOf(peer) } }
                own.await() to others.awaitAll().filterNotNull()
            }
        } else {
            null to emptyList()
        }
        val fromPhone = copies.mapNotNull { peer ->
            books.observeProgress(peer.bookId).first()?.takeIf { !it.mirrored }?.let {
                ServerProgress(it.positionMs, it.durationMs, it.isFinished, it.lastPlayedAt).withLengthOf(peer)
            }
        }
        val length = book.durationMs.takeIf { it > 0L } ?: local?.durationMs ?: 0L
        val others = (fromPhone + fromServers).mapNotNull { progressOnCopy(it, length) }
        return chooseStartPosition(local, newestAcrossCopies(server, others)) ?: startPositionFor(local, book.durationMs)
    }

    /** A copy's progress without a length gets the copy's own length, so it can be mapped. */
    private fun ServerProgress.withLengthOf(peer: MirrorPeer): ServerProgress =
        if (durationMs > 0L) this else copy(durationMs = peer.durationMs)

    private suspend fun peersOf(bookId: String): List<MirrorPeer> = try {
        peers.peersOf(bookId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** The book's downloaded files when all are on the phone; a failure to look counts as "not downloaded". */
    private suspend fun downloadedFiles(bookId: String): List<LocalFile>? = try {
        localFiles.complete(bookId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun downloadedOnly(): Boolean = try {
        settings.downloadedOnly.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun isOnline(): Boolean = try {
        connectivity.isOnline.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun serverProgress(bookId: String): ServerProgress? = try {
        withTimeoutOrNull(SERVER_WAIT_MS) { books.fetchServerProgress(bookId).getOrNull() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun continueLoaded() {
        val state = controller.state.value
        val running = state.isPlaying || (state.isBuffering && state.playWhenReady)
        if (!running) controller.play()
    }

    private suspend fun applySavedSpeed() {
        val speed = try {
            settings.playbackSpeed.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            1.0f
        }
        try {
            controller.setSpeed(speed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The book is loaded; it plays at whatever speed the player has.
        }
    }

    private companion object {
        const val SERVER_WAIT_MS = 4_000L
    }
}
