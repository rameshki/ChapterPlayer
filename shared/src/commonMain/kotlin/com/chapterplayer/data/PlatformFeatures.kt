package com.chapterplayer.data

/**
 * What this platform's build can do. The shared screens disable or hide the rest with a reason, never crash: iOS has
 * no downloads until Stage 5 step 5, and Android Auto and the home screen widgets are Android only. Android has
 * everything ([Full]); iOS streams ([IosStreaming]).
 */
data class PlatformFeatures(
    val playback: Boolean = true,
    val downloads: Boolean = true,
    /** Android Auto (its settings page). */
    val car: Boolean = true,
) {
    companion object {
        val Full = PlatformFeatures()

        /** No player, downloads or car: what a screen assumes when nobody said (fails closed). */
        val None = PlatformFeatures(playback = false, downloads = false, car = false)

        /** The iPhone build of Stage 5 step 2: browsing and sync only (kept for tests of a build without a player). */
        val IosBrowsingOnly = PlatformFeatures(playback = false, downloads = false, car = false)

        /** The iPhone build from Stage 5 step 4: browsing, sync and streaming playback; no downloads yet, no car. */
        val IosStreaming = PlatformFeatures(playback = true, downloads = false, car = false)
    }
}
