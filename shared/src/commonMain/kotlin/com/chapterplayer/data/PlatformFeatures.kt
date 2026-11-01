package com.chapterplayer.data

/**
 * What this platform's build can do. The shared screens disable or hide the rest with a reason, never crash: Android
 * Auto and the home screen widgets are Android only, and only Android lets the user choose where downloads go (an SD
 * card). Android has everything ([Full]); the iPhone build streams and downloads ([IosFull]).
 */
data class PlatformFeatures(
    val playback: Boolean = true,
    val downloads: Boolean = true,
    /** Android Auto (its settings page). */
    val car: Boolean = true,
    /** The download location choice in Settings (internal storage or an SD card); iOS has one place. */
    val downloadLocation: Boolean = true,
) {
    companion object {
        val Full = PlatformFeatures()

        /** No player, downloads or car: what a screen assumes when nobody said (fails closed). */
        val None = PlatformFeatures(playback = false, downloads = false, car = false, downloadLocation = false)

        /** The iPhone build of Stage 5 step 2: browsing and sync only (kept for tests of a build without a player). */
        val IosBrowsingOnly = PlatformFeatures(playback = false, downloads = false, car = false, downloadLocation = false)

        /** The iPhone build of Stage 5 step 4: browsing, sync and streaming playback; no downloads, no car (kept for tests). */
        val IosStreaming = PlatformFeatures(playback = true, downloads = false, car = false, downloadLocation = false)

        /** The iPhone build from Stage 5 step 5: streaming and downloads with offline playback; no car, one download place. */
        val IosFull = PlatformFeatures(playback = true, downloads = true, car = false, downloadLocation = false)
    }
}
