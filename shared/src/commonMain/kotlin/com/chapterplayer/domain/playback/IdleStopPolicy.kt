package com.chapterplayer.domain.playback

/** What the playback service does with its "stop after not playing" timer. */
enum class IdleTimerAction { ARM, CANCEL, NONE }

/**
 * The rule for the service's 10-minute "stop when not playing" timer (found on a real overnight journal, review R36): the
 * timer is armed when playback stops and cancelled when it starts, and once it has FIRED it is not armed again until
 * something plays. Without that last part the stop itself pauses the player, which re-arms the timer, which fires again
 * 10 minutes later, all night, while the app's own controller keeps the service bound: the process wakes six times an hour
 * for nothing and the journal fills with the same line.
 */
class IdleStopPolicy {
    private var fired = false

    /** What to do now that the player is [notPlaying] (paused, idle or ended), given whether a timer is already [timerActive]. */
    fun onState(notPlaying: Boolean, timerActive: Boolean): IdleTimerAction {
        if (!notPlaying) {
            fired = false
            return if (timerActive) IdleTimerAction.CANCEL else IdleTimerAction.NONE
        }
        return if (fired || timerActive) IdleTimerAction.NONE else IdleTimerAction.ARM
    }

    /** The timer ran out and the service was stopped: stay quiet until playback starts again. */
    fun onFired() {
        fired = true
    }
}
