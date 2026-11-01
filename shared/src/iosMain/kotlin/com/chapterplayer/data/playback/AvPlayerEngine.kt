package com.chapterplayer.data.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetPreferPreciseDurationAndTimingKey
import platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain
import platform.AVFoundation.actionAtItemEnd
import platform.AVFoundation.AVPlayerActionAtItemEndAdvance
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.errorLog
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUnderlyingErrorKey
import platform.UIKit.UIDevice
import platform.darwin.NSObjectProtocol

/**
 * [AudioEngine] over one `AVQueuePlayer` holding the playing file and the next one, so a book of several files plays on
 * without a gap. Seeking into another file rebuilds the queue there. A position asked for before an item is ready is kept
 * and applied once it is, and playing waits for it, so a book never starts from the file's beginning by mistake.
 *
 * State is read from the player when the controller asks (its ticker runs twice a second only while playing is asked
 * for). On top of that a poll every [POLL_MS] runs only while something is pending: an item still loading, a position
 * waiting for its item, a seek under way, or playing asked for; it stops once the book is paused and settled. A failure
 * of the current item is seen by that poll or by the failed-to-play notification, so an item that fails while paused
 * and settled is only noticed at the next Play. The end-of-item and stall notifications are always heard. Everything
 * runs on the main queue.
 *
 * Streamed items get the token through `AVURLAssetHTTPHeaderFieldsKey` (fixed headers per asset; AVFoundation has no
 * per-request hook short of a resource-loader proxy). The key is not in Apple's public headers: fine for a sideloaded
 * build, to be reviewed before an App Store build. AVFoundation follows redirects itself and keeps the headers, which is
 * why [StreamAuthorizer] checks the address (and its redirects) before an item is built.
 */
@OptIn(ExperimentalForeignApi::class)
class AvPlayerEngine(
    /**
     * Called before each start: the audio session must be active for sound to come out (and keep coming in the
     * background). False when the system refused: the start is reported as [EngineFailure.AUDIO_SESSION].
     */
    private val beforePlay: () -> Boolean = { true },
) : AudioEngine {

    private var player = newPlayer()
    private val scope = MainScope()
    private var listener: AudioEngine.Listener? = null

    private var items: List<EngineItem> = emptyList()
    private var headers: StreamHeaders = StreamHeaders.None
    private var index = 0
    /** The player items in the queue: the one playing first, then the next one (if any). */
    private var current: AVPlayerItem? = null
    private var next: AVPlayerItem? = null

    private var wantPlay = false
    private var rateWanted = 1.0f
    private var isEnded = false
    /** A position for [current] waiting for it to be ready. */
    private var pendingSeekMs: Long? = null
    /** A seek under way, shown as the position until it completes. */
    private var seekingToMs: Long? = null
    private var seekGeneration = 0
    /** Where [current] was last seen, for when the player cannot say (not ready, failed). */
    private var lastOffsetMs = 0L
    private var reportedFailure: AVPlayerItem? = null
    private var poll: Job? = null
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers += center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, queue) { n -> itemEnded(n) }
        observers += center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, null, queue) { n -> itemFailed(n) }
        observers += center.addObserverForName(AVPlayerItemPlaybackStalledNotification, null, queue) { _ -> changed() }
    }

    override fun setListener(listener: AudioEngine.Listener?) {
        this.listener = listener
    }

    override fun setQueue(items: List<EngineItem>, headers: StreamHeaders, index: Int, offsetMs: Long) {
        this.items = items
        this.headers = headers
        wantPlay = false
        rebuild(index, offsetMs)
    }

    override fun reload(headers: StreamHeaders, index: Int, offsetMs: Long) {
        this.headers = headers
        rebuild(index, offsetMs)
    }

    override fun updateHeaders(headers: StreamHeaders) {
        this.headers = headers
        // The queued next file is not playing yet: build it again with the new headers.
        val queued = next ?: return
        val playing = current ?: return
        if (player.currentItem != playing) return
        player.removeItem(queued)
        next = makeItem(index + 1)?.let { item -> if (insert(item, after = playing)) item else null }
    }

    /**
     * The phone's media services restarted and every player object is gone: a new player replaces the old one (empty;
     * the controller puts the book back with [setQueue]).
     */
    fun recreatePlayer() {
        player.pause()
        player.removeAllItems()
        player = newPlayer()
        current = null
        next = null
        pendingSeekMs = null
        seekingToMs = null
        poll?.cancel()
    }

    override fun clear() {
        wantPlay = false
        items = emptyList()
        player.pause()
        player.removeAllItems()
        current = null
        next = null
        index = 0
        pendingSeekMs = null
        seekingToMs = null
        lastOffsetMs = 0L
        isEnded = false
        poll?.cancel()
    }

    override fun play(rate: Float) {
        if (items.isEmpty() || isEnded) return
        rateWanted = rate
        if (!beforePlay()) {
            // No audio for us now (another app holds it): stay paused rather than buffer for ever.
            wantPlay = false
            player.pause()
            listener?.onItemFailed(index, EngineFailure.AUDIO_SESSION)
            return
        }
        wantPlay = true
        applyRate()
        startPolling()
    }

    override fun pause() {
        wantPlay = false
        player.pause()
    }

    override fun setRate(rate: Float) {
        rateWanted = rate
        applyRate()
    }

    override fun seekTo(index: Int, offsetMs: Long) {
        if (index !in items.indices) return
        // At the book's end the queue is empty (the last item was removed when it ended): build it again.
        val item = current?.takeIf { !isEnded && player.currentItem == it }
        isEnded = false
        if (index == this.index && item != null && item.status == AVPlayerItemStatusReadyToPlay) {
            seekInCurrent(offsetMs)
        } else if (index == this.index && item != null && item.status != AVPlayerItemStatusFailed) {
            pendingSeekMs = offsetMs
            lastOffsetMs = offsetMs
            startPolling()
        } else {
            rebuild(index, offsetMs)
        }
    }

    override val currentIndex: Int get() = index

    override val currentOffsetMs: Long
        get() {
            val length = items.getOrNull(index)?.durationMs ?: 0L
            if (isEnded) return length
            val known = pendingSeekMs ?: seekingToMs ?: run {
                val item = current
                if (item == null || item.status != AVPlayerItemStatusReadyToPlay || player.currentItem != item) {
                    lastOffsetMs
                } else {
                    val seconds = CMTimeGetSeconds(player.currentTime())
                    if (seconds.isNaN() || seconds < 0.0) lastOffsetMs else (seconds * 1000.0).toLong()
                }
            }
            lastOffsetMs = known
            return if (length > 0L) known.coerceIn(0L, length) else known.coerceAtLeast(0L)
        }

    override val isPlaying: Boolean
        get() = wantPlay && !isEnded && pendingSeekMs == null &&
            player.currentItem != null && player.timeControlStatus == AVPlayerTimeControlStatusPlaying

    override val isBuffering: Boolean get() = wantPlay && !isEnded && !isPlaying

    override val playWhenReady: Boolean get() = wantPlay && !isEnded

    override val ended: Boolean get() = isEnded

    override fun release() {
        clear()
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        listener = null
        scope.cancel()
    }

    // -- Queue -------------------------------------------------------------------------------

    private fun rebuild(index: Int, offsetMs: Long) {
        player.pause()
        player.removeAllItems()
        isEnded = false
        seekingToMs = null
        reportedFailure = null
        seekGeneration++
        this.index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        current = makeItem(this.index)?.let { if (insert(it, after = null)) it else null }
        next = current?.let { playing -> makeItem(this.index + 1)?.let { if (insert(it, after = playing)) it else null } }
        pendingSeekMs = offsetMs.takeIf { it > 0L }
        lastOffsetMs = offsetMs.coerceAtLeast(0L)
        if (current == null) {
            // An address the player cannot even read: the same as a failed item.
            scope.launch { listener?.onItemFailed(this@AvPlayerEngine.index, EngineFailure.OTHER) }
            return
        }
        applyRate()
        startPolling()
    }

    private fun makeItem(i: Int): AVPlayerItem? {
        val item = items.getOrNull(i) ?: return null
        val url = NSURL.URLWithString(item.url) ?: return null
        val options = mutableMapOf<Any?, Any?>()
        if (!item.isFile && !headers.isEmpty) options[HTTP_HEADER_FIELDS_KEY] = headers.values
        // MP3 has no index: exact timing makes seeks and the length right (slower to open, worth it for books).
        if (isMp3(item)) options[AVURLAssetPreferPreciseDurationAndTimingKey] = true
        // An address without an extension (Audiobookshelf's /file/{ino}): the type the server named, from iOS 17.
        if (item.mimeType.isNotBlank() && !isPlaylist(item) && overrideMimeAvailable) options[OVERRIDE_MIME_TYPE_KEY] = item.mimeType
        val asset = AVURLAsset.URLAssetWithURL(url, options.ifEmpty { null })
        return AVPlayerItem.playerItemWithAsset(asset).also {
            // Speech at other speeds keeps its pitch and stays clear.
            it.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
        }
    }

    // Plays at the wanted rate when playing is asked for and no position is waiting; otherwise stays still.
    private fun applyRate() {
        if (wantPlay && !isEnded && pendingSeekMs == null && seekingToMs == null) {
            player.rate = rateWanted
        } else if (!wantPlay) {
            player.pause()
        }
    }

    private fun seekInCurrent(offsetMs: Long) {
        val generation = ++seekGeneration
        seekingToMs = offsetMs
        lastOffsetMs = offsetMs
        // Half a second either side: exact seeks in long MP3 files can take very long; chapters stay accurate enough.
        val tolerance = CMTimeMake(1, 2)
        player.seekToTime(CMTimeMakeWithSeconds(offsetMs / 1000.0, 1000), tolerance, tolerance) { _ ->
            scope.launch {
                if (generation != seekGeneration) return@launch
                seekingToMs = null
                applyRate()
                changed()
            }
        }
    }

    // -- Watching ------------------------------------------------------------------------------

    private fun startPolling() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (!check()) break
            }
        }
    }

    /** One look at the player; false when nothing is pending any more (the poll stops). */
    private fun check(): Boolean {
        val item = current ?: return false
        // The queue moved on without the notification reaching us (it should not happen, but the index must follow).
        if (player.currentItem != null && player.currentItem == next) advance()
        when (item.status) {
            AVPlayerItemStatusFailed -> {
                reportFailure(item, item.error)
                return false
            }
            AVPlayerItemStatusReadyToPlay -> {
                val waiting = pendingSeekMs
                if (waiting != null) {
                    pendingSeekMs = null
                    seekInCurrent(waiting)
                }
            }
        }
        changed()
        return wantPlay || pendingSeekMs != null || seekingToMs != null || item.status != AVPlayerItemStatusReadyToPlay
    }

    private fun itemEnded(notification: NSNotification?) {
        val item = notification?.`object` as? AVPlayerItem ?: return
        if (item != current) return
        if (index >= items.size - 1) {
            // The book is over: stay at its end.
            isEnded = true
            wantPlay = false
            player.pause()
            listener?.onEnded()
        } else {
            advance()
            changed()
        }
    }

    // The player went on to the next file: it becomes the current one and the one after it is queued.
    private fun advance() {
        index++
        current = next
        lastOffsetMs = 0L
        pendingSeekMs = null
        seekingToMs = null
        reportedFailure = null
        next = current?.let { playing -> makeItem(index + 1)?.let { if (insert(it, after = playing)) it else null } }
    }

    // The queue refuses an item it cannot take (already queued elsewhere, a player in a bad state): checked, never thrown.
    private fun insert(item: AVPlayerItem, after: AVPlayerItem?): Boolean {
        if (!player.canInsertItem(item, afterItem = after)) return false
        player.insertItem(item, afterItem = after)
        return true
    }

    private fun itemFailed(notification: NSNotification?) {
        val item = notification?.`object` as? AVPlayerItem ?: return
        if (item != current) return
        reportFailure(item, notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError)
    }

    private fun reportFailure(item: AVPlayerItem, error: NSError?) {
        if (reportedFailure == item) return
        reportedFailure = item
        // Keep the place: the controller rebuilds the item here after checking the address again.
        lastOffsetMs = currentOffsetMs
        player.pause()
        listener?.onItemFailed(index, classifyAppleMediaError(errorChain(error), httpStatusOf(item)))
    }

    private fun changed() {
        listener?.onChanged()
    }

    private companion object {
        /** The asset option that sets HTTP request headers (not in the public headers; see the class comment). */
        const val HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"
        /** `AVURLAssetOverrideMIMETypeKey` (iOS 17), by its string so the app still loads on iOS 16. */
        const val OVERRIDE_MIME_TYPE_KEY = "AVURLAssetOverrideMIMETypeKey"
        const val POLL_MS = 200L

        val overrideMimeAvailable: Boolean by lazy {
            (UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 0) >= 17
        }

        fun isMp3(item: EngineItem): Boolean =
            item.mimeType.lowercase() in setOf("audio/mpeg", "audio/mp3") ||
                item.url.substringBefore('?').lowercase().endsWith(".mp3")

        fun isPlaylist(item: EngineItem): Boolean =
            item.mimeType.lowercase().contains("mpegurl") || item.url.substringBefore('?').lowercase().endsWith(".m3u8")

        fun newPlayer(): AVQueuePlayer = AVQueuePlayer().also {
            it.actionAtItemEnd = AVPlayerActionAtItemEndAdvance
            it.automaticallyWaitsToMinimizeStalling = true
        }
    }
}

/** An error and its underlying errors (at most a few), as domain and code only. */
@OptIn(ExperimentalForeignApi::class)
internal fun errorChain(error: NSError?): List<PlatformErrorCode> {
    val chain = mutableListOf<PlatformErrorCode>()
    var e = error
    while (e != null && chain.size < 5) {
        chain += PlatformErrorCode(e.domain.orEmpty(), e.code)
        e = e.userInfo[NSUnderlyingErrorKey] as? NSError
    }
    return chain
}

/** The HTTP status the item's error log names last, if any (AVFoundation logs HTTP failures there). */
@OptIn(ExperimentalForeignApi::class)
private fun httpStatusOf(item: AVPlayerItem): Int? =
    item.errorLog()?.events?.lastOrNull()?.let { (it as? AVPlayerItemErrorLogEvent)?.errorStatusCode?.toInt() }
        ?.takeIf { it in 100..599 }
