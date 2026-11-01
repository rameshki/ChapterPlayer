package com.chapterplayer.domain.playback

import com.chapterplayer.data.model.Chapter
import com.chapterplayer.data.playback.PlaybackState
import com.chapterplayer.domain.browse.currentChapterIndex
import kotlin.math.abs

/*
 * The player's decisions about the phone's audio system (interruptions, route changes, lock screen and headset
 * commands, the Now Playing card), free of any platform API so they are tested on every target. iOS feeds them from
 * AVAudioSession, MPRemoteCommandCenter and MPNowPlayingInfoCenter (IosPlaybackController); Android's Media3 session
 * makes the same decisions itself.
 */

/** What to do when an audio interruption (a phone call, Siri, an alarm, another app's audio) ends. */
enum class AfterInterruption { RESUME, STAY_PAUSED }

/**
 * Playback resumes only when the book was playing (or about to, while buffering) as the interruption began and the
 * system says it may (iOS: the "should resume" option). A book the listener had paused stays paused, and so does one
 * whose audio another app took for good.
 */
fun decideAfterInterruption(wasPlaying: Boolean, shouldResume: Boolean): AfterInterruption =
    if (wasPlaying && shouldResume) AfterInterruption.RESUME else AfterInterruption.STAY_PAUSED

/** Why the audio route changed, as far as playback cares. */
enum class RouteChange {
    /** Headphones unplugged, a Bluetooth device gone. */
    OLD_DEVICE_UNAVAILABLE,
    NEW_DEVICE_AVAILABLE,
    OTHER,
}

/** Headphones that go away pause the book (it must not suddenly play out loud); nothing else changes playback. */
fun pausesOnRouteChange(change: RouteChange, playWhenReady: Boolean): Boolean =
    change == RouteChange.OLD_DEVICE_UNAVAILABLE && playWhenReady

/** A command from the lock screen, Control Center, a headset or a Bluetooth device. */
sealed interface RemoteCommand {
    data object Play : RemoteCommand
    data object Pause : RemoteCommand
    data object TogglePlayPause : RemoteCommand
    data object SkipForward : RemoteCommand
    data object SkipBack : RemoteCommand
    /** The listener dragged the progress bar to [positionMs] (on the book's timeline). */
    data class ChangePosition(val positionMs: Long) : RemoteCommand
    data object NextChapter : RemoteCommand
    data object PreviousChapter : RemoteCommand
}

/** What the player does for a [RemoteCommand]. */
sealed interface RemoteAction {
    data object Play : RemoteAction
    data object Pause : RemoteAction
    data object SkipForward : RemoteAction
    data object SkipBack : RemoteAction
    data class SeekTo(val positionMs: Long) : RemoteAction
    /** Nothing to do: no book is loaded, or there is no chapter to go to. */
    data object None : RemoteAction
}

/**
 * The action for [command] given what plays now. Without a loaded book nothing happens (the app loads books; the lock
 * screen has nothing to resume). Toggle pauses a book that is playing or about to (buffering). Next chapter at the last
 * chapter does nothing; previous chapter restarts the chapter after its first 3 seconds (as the player screen does).
 */
fun remoteAction(command: RemoteCommand, state: PlaybackState): RemoteAction {
    if (state.bookId == null) return RemoteAction.None
    return when (command) {
        RemoteCommand.Play -> RemoteAction.Play
        RemoteCommand.Pause -> RemoteAction.Pause
        RemoteCommand.TogglePlayPause -> if (state.playWhenReady || state.isPlaying) RemoteAction.Pause else RemoteAction.Play
        RemoteCommand.SkipForward -> RemoteAction.SkipForward
        RemoteCommand.SkipBack -> RemoteAction.SkipBack
        is RemoteCommand.ChangePosition -> {
            val end = state.durationMs.coerceAtLeast(0L)
            RemoteAction.SeekTo(command.positionMs.coerceIn(0L, end))
        }
        RemoteCommand.NextChapter ->
            nextChapterStart(state.chapters, state.positionMs)?.let { RemoteAction.SeekTo(it) } ?: RemoteAction.None
        RemoteCommand.PreviousChapter ->
            if (state.chapters.isEmpty()) RemoteAction.None else RemoteAction.SeekTo(previousChapterStart(state.chapters, state.positionMs))
    }
}

/** Which remote commands the lock screen offers: transport only with a book, chapter jumps only with two chapters or more. */
data class RemoteCommandsEnabled(val transport: Boolean, val chapters: Boolean)

fun remoteCommandsEnabled(state: PlaybackState): RemoteCommandsEnabled =
    RemoteCommandsEnabled(transport = state.bookId != null, chapters = state.bookId != null && state.chapters.size > 1)

/** The lock screen and Control Center card. Times are seconds, as the system takes them. No artwork (covers need the token). */
data class NowPlayingInfo(
    val title: String,
    val artist: String,
    /** The chapter the position is in, shown under the title; null for a book without chapters. */
    val chapterTitle: String?,
    val durationSec: Double,
    val elapsedSec: Double,
    /** 0 while paused or buffering: the system moves the elapsed time on by this rate itself. */
    val rate: Double,
    /** The speed chosen, also while paused. */
    val defaultRate: Double,
    /** 0-based, as the system counts; null without chapters. */
    val chapterNumber: Int?,
    val chapterCount: Int,
)

/** The card for [state] of the book called [title] by [author]; null when nothing is loaded (the card is cleared). */
fun nowPlayingInfo(state: PlaybackState, title: String, author: String): NowPlayingInfo? {
    if (state.bookId == null) return null
    val chapters: List<Chapter> = state.chapters
    val index = state.chapterIndex ?: currentChapterIndex(chapters, state.positionMs)
    return NowPlayingInfo(
        title = title,
        artist = author,
        chapterTitle = index?.let { chapters.getOrNull(it)?.title }?.takeIf { it.isNotBlank() },
        durationSec = state.durationMs.coerceAtLeast(0L) / 1000.0,
        elapsedSec = state.positionMs.coerceAtLeast(0L) / 1000.0,
        rate = if (state.isPlaying) state.speed.toDouble() else 0.0,
        defaultRate = state.speed.toDouble(),
        chapterNumber = index?.takeIf { chapters.isNotEmpty() },
        chapterCount = chapters.size,
    )
}

/** How far the card's own clock may drift from the player before it is set again. */
const val NOW_PLAYING_DRIFT_SEC = 1.5

/**
 * Whether the card must be set again. The system moves the elapsed time on by the rate, so ticks that only advance the
 * position need nothing; a seek, a pause, a speed or chapter change, or a drift past [NOW_PLAYING_DRIFT_SEC] do.
 * [shownAtMs] and [nowMs] are any steady clock in milliseconds.
 */
fun nowPlayingNeedsUpdate(shown: NowPlayingInfo?, shownAtMs: Long, next: NowPlayingInfo?, nowMs: Long): Boolean {
    if (shown == null || next == null) return shown != next
    if (shown.copy(elapsedSec = 0.0) != next.copy(elapsedSec = 0.0)) return true
    val expected = shown.elapsedSec + shown.rate * (nowMs - shownAtMs).coerceAtLeast(0L) / 1000.0
    return abs(expected - next.elapsedSec) > NOW_PLAYING_DRIFT_SEC
}

/** What a book that has stayed paused for a while does. */
enum class IdleAction {
    NOTHING,
    /** Close the server session only: the book, its place and the lock screen card stay; Play opens a new session. */
    CLOSE_SESSION,
    /** Close the session and forget the book (Android: its service stops, the notification goes). */
    END_ALL,
}

/**
 * The idle rule, checked again when its timer fires (a timer that fires late, after the phone woke, must not undo a Play
 * that came first): nothing while playing is asked for or before [limitMs] of pause; then [IdleAction.CLOSE_SESSION]
 * where the lock screen should keep the book ([keepsBook], iOS) or [IdleAction.END_ALL].
 */
fun idleAction(playWhenReady: Boolean, pausedForMs: Long, limitMs: Long, keepsBook: Boolean): IdleAction = when {
    playWhenReady || pausedForMs < limitMs -> IdleAction.NOTHING
    keepsBook -> IdleAction.CLOSE_SESSION
    else -> IdleAction.END_ALL
}
