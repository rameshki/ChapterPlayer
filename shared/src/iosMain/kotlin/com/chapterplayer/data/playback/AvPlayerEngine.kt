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
import platform.darwin.NSObjectProtocol

/**
 * [AudioEngine] over one `AVQueuePlayer` holding the playing file and the next one, so a book of several files plays on
 * without a gap. Seeking into another file rebuilds the queue there. A position asked for before an item is ready is kept
 * and applied once it is, and playing waits for it, so a book never starts from the file's beginning by mistake.
 *
 * State is read from the player when asked (the controller ticks twice a second while playing) plus a poll every
 * [POLL_MS] while something is pending (an item loading, a seek, playing asked for), and the end-of-item, failure and
 * stall notifications. Everything runs on the main queue.
 *
 * Streamed items get the token through `AVURLAssetHTTPHeaderFieldsKey` (fixed headers per asset; AVFoundation has no
 * per-request hook short of a resource-loader proxy). The key is not in Apple's public headers: fine for a sideloaded
 * build, to be reviewed before an App Store build. AVFoundation follows redirects itself and keeps the headers, which is
 * why [StreamAuthorizer] checks the address (and its redirects) before an item is built.
 */
@OptIn(ExperimentalForeignApi::class)
class AvPlayerEngine(
    /** Called before each start: the audio session must be active for sound to come out (and keep coming in the background). */
    private val beforePlay: () -> Unit = {},
) : AudioEngine {

    private val player = AVQueuePlayer()
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
        player.actionAtItemEnd = AVPlayerActionAtItemEndAdvance
        player.automaticallyWaitsToMinimizeStalling = true
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
        wantPlay = true
        beforePlay()
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
        isEnded = false
        val item = current
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
        current = makeItem(this.index)?.also { player.insertItem(it, afterItem = null) }
        next = makeItem(this.index + 1)?.also { item -> current?.let { player.insertItem(item, afterItem = it) } }
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
        val options: Map<Any?, Any?>? =
            if (item.isFile || headers.isEmpty) null else mapOf(HTTP_HEADER_FIELDS_KEY to headers.values)
        val asset = AVURLAsset.URLAssetWithURL(url, options)
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
        next = makeItem(index + 1)?.also { item -> current?.let { player.insertItem(item, afterItem = it) } }
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
        const val POLL_MS = 200L
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
