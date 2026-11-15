package com.chapterplayer.domain.playback

/**
 * Android 15 (targetSdk 35 and later) grants audio focus only to the top app or an app running a foreground service.
 * A Play from outside the app (a widget's headless Play, the car, a Bluetooth button) reaches the player before Media3
 * has put the playback service in the foreground, so the focus request can be refused: ExoPlayer then turns
 * playWhenReady off with the reason "audio focus loss" before any sound played, and Media3 never starts the
 * foreground service (so its foreground refusal callback never fires either). That is not a real loss of focus to
 * another app; the person asked for sound and got none. This watch tells the two apart so the service can react like a
 * refused foreground start (pause, "Tap to keep listening").
 */
class AudioFocusStartWatch {
    /** Play was asked for and no sound has played since. */
    private var waitingForSound = false

    /** When Play was asked for (a monotonic clock in ms). */
    private var playAskedAtMs = 0L

    /**
     * playWhenReady changed; [byFocusLoss] when ExoPlayer changed it for audio focus, [appInFront] whether the app is the
     * top app, [nowMs] a monotonic clock. True when this is a refused focus request at a start (to be told to the
     * person), false otherwise. A refusal arrives within milliseconds of Play, so only a loss within
     * [FOCUS_REFUSAL_WINDOW_MS] counts; a later one (say during slow buffering) is another app taking the audio.
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, byFocusLoss: Boolean, appInFront: Boolean, nowMs: Long): Boolean {
        if (playWhenReady) {
            waitingForSound = true
            playAskedAtMs = nowMs
            return false
        }
        val refused = byFocusLoss && waitingForSound && !appInFront && nowMs - playAskedAtMs in 0..FOCUS_REFUSAL_WINDOW_MS
        waitingForSound = false
        return refused
    }

    /** Sound is playing: a later loss of focus is a real one (another app took the audio). */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) waitingForSound = false
    }
}

/** How soon after Play a focus loss still counts as a refused request (refusals come within milliseconds). */
const val FOCUS_REFUSAL_WINDOW_MS: Long = 1_000
