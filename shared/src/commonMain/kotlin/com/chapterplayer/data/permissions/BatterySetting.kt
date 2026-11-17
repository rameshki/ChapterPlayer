package com.chapterplayer.data.permissions

/**
 * Whether Android may stop the app in the background to save battery, which can end playback overnight (review R36).
 * The app asks the person to allow it: [openSettings] opens the system's list, where the person picks the app. No
 * permission is needed for that (the direct dialog needs one that Google Play restricts).
 */
interface BatterySetting {
    /** False where the idea does not apply (iOS). */
    val available: Boolean

    /** The app is on the system's list of apps that may run in the background without limits. Read on every call. */
    fun isUnrestricted(): Boolean

    /** Opens the system's battery screen for the person to change it. Never throws. */
    fun openSettings()
}

/** No battery setting (iOS, and tests). */
object NoBatterySetting : BatterySetting {
    override val available = false
    override fun isUnrestricted() = true
    override fun openSettings() = Unit
}
