package com.chapterplayer.data

/**
 * What this platform's build can do. The shared screens disable or hide the rest with a reason, never crash: iOS has
 * no player until Stage 5 step 4 and no downloads until step 5, and Android Auto and the home screen widgets are
 * Android only. Android has everything ([Full]).
 */
data class PlatformFeatures(
    val playback: Boolean = true,
    val downloads: Boolean = true,
    /** Android Auto (its settings page). */
    val car: Boolean = true,
) {
    companion object {
        val Full = PlatformFeatures()

        /** The iPhone build of Stage 5 step 2: browsing and sync only. */
        val IosBrowsingOnly = PlatformFeatures(playback = false, downloads = false, car = false)
    }
}
