package com.chapterplayer.data.stats

import com.chapterplayer.domain.stats.DayZone
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

/** The phone's time zone as it is set now (`localTimeZone` follows a change the user or the network makes). */
object IosDayZone : DayZone {
    override fun offsetMsAt(epochMs: Long): Long =
        NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L
}

/**
 * Darwin's CLOCK_MONOTONIC (keeps counting while the phone sleeps and never jumps, like Android's elapsed real time)
 * and the wall clock.
 */
@OptIn(ExperimentalForeignApi::class)
object IosListeningClock : ListeningClock {
    override fun monoMs(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / 1_000_000uL).toLong()
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
}
