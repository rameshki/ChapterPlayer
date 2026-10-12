package com.chapterplayer.data.model

/**
 * A single chapter within an audiobook.
 * Timestamps are in milliseconds for direct use with Media3/ExoPlayer.
 */
data class Chapter(
    val id: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long
) {
    /** Duration of this chapter in milliseconds. */
    val durationMs: Long get() = endMs - startMs
}
