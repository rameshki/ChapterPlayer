package com.chapterplayer.data.stats

import com.chapterplayer.domain.stats.DayZone
import java.util.TimeZone

/** The phone's time zone as it is set now (Android resets the default when the user or the network changes it). */
object SystemDayZone : DayZone {
    override fun offsetMsAt(epochMs: Long): Long = TimeZone.getDefault().getOffset(epochMs).toLong()
}

/** Elapsed real time (counts deep sleep, never jumps) and the wall clock. */
object AndroidListeningClock : ListeningClock {
    override fun monoMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun nowMs(): Long = System.currentTimeMillis()
}
