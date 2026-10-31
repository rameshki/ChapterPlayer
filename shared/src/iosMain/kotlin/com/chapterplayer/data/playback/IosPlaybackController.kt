package com.chapterplayer.data.playback

import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.domain.playback.NowPlayingInfo
import com.chapterplayer.domain.playback.RemoteCommand
import com.chapterplayer.domain.playback.RouteChange
import com.chapterplayer.domain.playback.nowPlayingInfo
import com.chapterplayer.domain.playback.nowPlayingNeedsUpdate
import com.chapterplayer.domain.playback.remoteCommandsEnabled
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionRouteSharingPolicyLongFormAudio
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingInfoPropertyChapterCount
import platform.MediaPlayer.MPNowPlayingInfoPropertyChapterNumber
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusNoActionableNowPlayingItem
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObjectProtocol

/**
 * The iPhone's [PlaybackController]: the shared [EnginePlaybackController] over [AvPlayerEngine], plus what iOS needs
 * around it: the audio session (playback category, spoken audio mode, long-form route sharing, made active before each
 * start, so audio goes on in the background with `UIBackgroundModes: audio`), interruptions and route changes (decided by
 * the shared rules), the lock screen / Control Center / headset commands (play, pause, toggle, 30 s forward, 15 s back,
 * drag the position, next and previous chapter) and the Now Playing card (title, author, chapter, length, position,
 * speed; no artwork, as on Android, because covers need the token). After 10 minutes paused only the server session
 * closes and the audio session is let go (other apps' audio may resume); the book, its place and the card stay, and Play
 * (here or from the lock screen) opens a new session. A media services reset sets the session up again on a new player
 * at the same place. Recovery from a failed item runs inside a UIKit background task. A book with Ogg/Opus parts (Plex)
 * fails with a clear message ([applePlays]); Audiobookshelf sends such books as HLS (the iOS play request leaves them
 * out). Built on the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlaybackController private constructor(
    private val core: EnginePlaybackController,
    private val engine: AvPlayerEngine,
) : PlaybackController by core {

    private val scope = MainScope()
    private val observers = mutableListOf<NSObjectProtocol>()
    private val commandTargets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var shown: NowPlayingInfo? = null
    private var shownAtMs = 0L

    init {
        configureAudioSession()
        watchAudioSession()
        registerRemoteCommands()
        scope.launch {
            combine(core.state, core.label) { state, label -> state to label }.collect { (state, label) ->
                updateNowPlaying(state, label)
                updateCommands(state)
            }
        }
    }

    // -- Audio session -------------------------------------------------------------------------

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        // Spoken audio: other apps' speech pauses rather than ducks; long-form audio: AirPlay and car routes as for books.
        if (!session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeSpokenAudio, AVAudioSessionRouteSharingPolicyLongFormAudio, 0uL, null)) {
            session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeSpokenAudio, 0uL, null)
        }
    }

    private fun watchAudioSession() {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, null, queue) { n -> interruption(n) }
        observers += center.addObserverForName(AVAudioSessionRouteChangeNotification, null, queue) { n -> routeChange(n) }
        observers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, null, queue) { _ -> mediaServicesReset() }
    }

    // Apple: after a media services reset every audio object is invalid; set the session up again and build a new player.
    private fun mediaServicesReset() {
        configureAudioSession()
        engine.recreatePlayer()
        core.onMediaServicesReset()
    }

    private fun interruption(n: NSNotification?) {
        val info = n?.userInfo ?: return
        when ((info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue) {
            AVAudioSessionInterruptionTypeBegan -> core.onInterruptionBegan()
            AVAudioSessionInterruptionTypeEnded -> {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0uL
                core.onInterruptionEnded(shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL)
            }
        }
    }

    private fun routeChange(n: NSNotification?) {
        val reason = (n?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
        core.onRouteChange(
            when (reason) {
                AVAudioSessionRouteChangeReasonOldDeviceUnavailable -> RouteChange.OLD_DEVICE_UNAVAILABLE
                AVAudioSessionRouteChangeReasonNewDeviceAvailable -> RouteChange.NEW_DEVICE_AVAILABLE
                else -> RouteChange.OTHER
            }
        )
    }

    // -- Remote commands -----------------------------------------------------------------------

    private fun registerRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.skipForwardCommand.preferredIntervals = listOf(NSNumber(double = EnginePlaybackController.SKIP_FORWARD_MS / 1000.0))
        center.skipBackwardCommand.preferredIntervals = listOf(NSNumber(double = EnginePlaybackController.SKIP_BACK_MS / 1000.0))
        on(center.playCommand) { RemoteCommand.Play }
        on(center.pauseCommand) { RemoteCommand.Pause }
        on(center.togglePlayPauseCommand) { RemoteCommand.TogglePlayPause }
        on(center.skipForwardCommand) { RemoteCommand.SkipForward }
        on(center.skipBackwardCommand) { RemoteCommand.SkipBack }
        on(center.nextTrackCommand) { RemoteCommand.NextChapter }
        on(center.previousTrackCommand) { RemoteCommand.PreviousChapter }
        on(center.changePlaybackPositionCommand) { event ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime ?: return@on null
            RemoteCommand.ChangePosition((seconds * 1000.0).toLong())
        }
        updateCommands(core.state.value)
    }

    private fun on(command: MPRemoteCommand, toCommand: (MPRemoteCommandEvent?) -> RemoteCommand?) {
        val target = command.addTargetWithHandler { event ->
            val remote = toCommand(event)
            when {
                remote == null -> MPRemoteCommandHandlerStatusCommandFailed
                core.onRemoteCommand(remote) -> MPRemoteCommandHandlerStatusSuccess
                else -> MPRemoteCommandHandlerStatusNoActionableNowPlayingItem
            }
        }
        commandTargets += command to target
    }

    private fun updateCommands(state: PlaybackState) {
        val enabled = remoteCommandsEnabled(state)
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        listOf(
            center.playCommand, center.pauseCommand, center.togglePlayPauseCommand, center.skipForwardCommand,
            center.skipBackwardCommand, center.changePlaybackPositionCommand,
        ).forEach { it.enabled = enabled.transport }
        center.nextTrackCommand.enabled = enabled.chapters
        center.previousTrackCommand.enabled = enabled.chapters
    }

    // -- Now Playing ---------------------------------------------------------------------------

    private fun updateNowPlaying(state: PlaybackState, label: BookLabel?) {
        val next = label?.let { nowPlayingInfo(state, it.title, it.author) }
        val now = monotonicMs()
        if (!nowPlayingNeedsUpdate(shown, shownAtMs, next, now)) return
        shown = next
        shownAtMs = now
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = next?.let(::toDictionary)
    }

    private fun toDictionary(info: NowPlayingInfo): Map<Any?, Any?> = buildMap {
        put(MPMediaItemPropertyTitle, info.title)
        put(MPMediaItemPropertyArtist, info.artist)
        info.chapterTitle?.let { put(MPMediaItemPropertyAlbumTitle, it) }
        put(MPMediaItemPropertyPlaybackDuration, NSNumber(double = info.durationSec))
        put(MPNowPlayingInfoPropertyElapsedPlaybackTime, NSNumber(double = info.elapsedSec))
        put(MPNowPlayingInfoPropertyPlaybackRate, NSNumber(double = info.rate))
        put(MPNowPlayingInfoPropertyDefaultPlaybackRate, NSNumber(double = info.defaultRate))
        put(MPNowPlayingInfoPropertyMediaType, NSNumber(unsignedLong = MPNowPlayingInfoMediaTypeAudio))
        info.chapterNumber?.let { put(MPNowPlayingInfoPropertyChapterNumber, NSNumber(long = it.toLong())) }
        if (info.chapterCount > 0) put(MPNowPlayingInfoPropertyChapterCount, NSNumber(long = info.chapterCount.toLong()))
    }

    override fun release() {
        scope.cancel()
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        commandTargets.forEach { (command, target) -> command.removeTarget(target) }
        commandTargets.clear()
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
        core.release()
    }

    companion object {
        /** The app's one player, over AVPlayer. Call on the main thread. */
        fun create(
            repository: BookRepository,
            streams: StreamAuthorizer,
            appScope: CoroutineScope,
            localFiles: LocalBookFiles,
        ): IosPlaybackController {
            val engine = AvPlayerEngine(beforePlay = ::activateAudioSession)
            val core = EnginePlaybackController(
                engine = engine,
                repository = repository,
                streams = streams,
                appScope = appScope,
                localFiles = localFiles,
                fileUri = { path -> NSURL.fileURLWithPath(path).absoluteString ?: "file://$path" },
                clock = ::monotonicMs,
                main = Dispatchers.Main,
                // The lock screen keeps the book after a long pause; Play opens a new session.
                keepsBookWhenIdle = true,
                onIdle = ::deactivateAudioSession,
                background = UiKitBackgroundWork,
                canPlay = ::applePlays,
            )
            return IosPlaybackController(core, engine)
        }

        /** Sound only comes out (and keeps coming in the background) while the session is active; asking again is harmless. */
        private fun activateAudioSession(): Boolean = AVAudioSession.sharedInstance().setActive(true, null)

        /** Long paused or nothing left to play: other apps' audio may go on (a podcast another app paused for us). */
        private fun deactivateAudioSession() {
            AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        }
    }
}

/** A UIKit background task: a few seconds more for a recovery that started while the app was in the background. */
private object UiKitBackgroundWork : BackgroundWork {
    override fun begin(onExpired: () -> Unit): () -> Unit {
        val app = UIApplication.sharedApplication
        var id = UIBackgroundTaskInvalid
        var ended = false
        val end = {
            if (!ended && id != UIBackgroundTaskInvalid) {
                ended = true
                app.endBackgroundTask(id)
            }
        }
        id = app.beginBackgroundTaskWithExpirationHandler {
            // Called on the main thread when time is up: stop cleanly, then the task must end.
            onExpired()
            end()
        }
        return end
    }
}

/** A steady clock in milliseconds (time since boot, not the wall clock). */
private fun monotonicMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()
