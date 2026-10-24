package com.chapterplayer.domain.downloads

import kotlin.math.ceil

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, FAILED, DONE }

data class FileProgress(val totalBytes: Long, val doneBytes: Long)

/** Bytes done over bytes total across all files, 0 to 1; 0 when the total is 0. */
fun overallFraction(files: List<FileProgress>): Float {
    val total = files.sumOf { it.totalBytes }
    if (total <= 0L) return 0f
    val done = files.sumOf { it.doneBytes }
    return (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** Download speed over a rolling window of (time, total bytes so far) samples. */
class SpeedTracker(private val windowMs: Long = 30_000) {
    private val samples = ArrayList<Pair<Long, Long>>()

    fun add(nowMs: Long, bytesTotalSoFar: Long) {
        if (samples.isNotEmpty() && bytesTotalSoFar < samples.last().second) samples.clear()
        samples.add(nowMs to bytesTotalSoFar)
        while (samples.size > 1 && nowMs - samples.first().first > windowMs) samples.removeAt(0)
    }

    fun bytesPerSecond(): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val ms = last.first - first.first
        if (ms <= 0L) return 0.0
        return (last.second - first.second) * 1000.0 / ms
    }
}

/** Whole seconds left (rounded up); null when the speed is unknown; 0 when nothing remains. */
fun etaSeconds(remainingBytes: Long, bytesPerSecond: Double): Long? {
    if (remainingBytes <= 0L) return 0L
    if (bytesPerSecond <= 0.0) return null
    return ceil(remainingBytes / bytesPerSecond).toLong()
}

/** Index of the first file that is not complete, or null when all are. */
fun nextFileIndex(files: List<FileProgress>): Int? =
    files.indexOfFirst { it.doneBytes < it.totalBytes }.takeIf { it >= 0 }

/** Where to resume a partly downloaded file: the smaller of disk and record, or 0 (start over) when either is out of range. */
fun validResumeOffset(partFileLength: Long, recordedDoneBytes: Long, totalBytes: Long): Long =
    if (partFileLength in 0..totalBytes && recordedDoneBytes in 0..totalBytes) minOf(partFileLength, recordedDoneBytes) else 0L

/** Wait before retry number [attempt]: 5 s doubling, at most 5 minutes. */
fun backoffMs(attempt: Int): Long {
    var ms = 5_000L
    repeat(attempt.coerceAtLeast(1) - 1) {
        ms *= 2
        if (ms >= 300_000L) return 300_000L
    }
    return ms
}

enum class NetworkPolicy { WIFI_ONLY, WIFI_AND_CELLULAR }

fun mayDownload(policy: NetworkPolicy, onUnmetered: Boolean, online: Boolean): Boolean =
    online && (policy == NetworkPolicy.WIFI_AND_CELLULAR || onUnmetered)

/** False when the phone would be left with under [safetyBytes] free, or a set limit would be passed. */
fun hasRoomFor(
    neededBytes: Long,
    freeBytes: Long,
    limitBytes: Long?,
    usedBytes: Long,
    safetyBytes: Long = 100L * 1024 * 1024
): Boolean {
    if (freeBytes < neededBytes + safetyBytes) return false
    if (limitBytes != null && usedBytes + neededBytes > limitBytes) return false
    return true
}
