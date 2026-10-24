package com.chapterplayer.domain.downloads

/** A gigabyte as phones show storage: 1000 x 1000 x 1000 bytes. */
const val GIGABYTE: Long = 1_000_000_000L
private const val MEGABYTE: Long = 1_000_000L

/** The storage limits Settings offers; null is "No limit". */
val STORAGE_LIMIT_OPTIONS: List<Long?> = listOf(null) + listOf(1L, 2L, 5L, 10L, 20L, 50L).map { it * GIGABYTE }

/**
 * A size as "350 MB" (whole megabytes, at least 1 MB for anything above 0) or, from a gigabyte up,
 * "1.2 GB" (tenths, with ".0" left out). Negative sizes read as 0.
 */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val megabytes = (b + MEGABYTE / 2) / MEGABYTE
    if (megabytes < 1000L) return "${if (b > 0L) megabytes.coerceAtLeast(1L) else 0L} MB"
    val tenths = (b + GIGABYTE / 20) / (GIGABYTE / 10)
    val whole = tenths / 10
    val rest = tenths % 10
    return if (rest == 0L) "$whole GB" else "$whole.$rest GB"
}

fun storageLimitLabel(limitBytes: Long?): String = if (limitBytes == null) "No limit" else formatBytes(limitBytes)

/** The Downloads tab header: "1.2 GB used · Free: 30 GB". */
fun storageHeaderText(usedBytes: Long, freeBytes: Long): String = "${formatBytes(usedBytes)} used · Free: ${formatBytes(freeBytes)}"
