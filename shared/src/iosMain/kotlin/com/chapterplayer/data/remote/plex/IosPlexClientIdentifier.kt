package com.chapterplayer.data.remote.plex

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

/**
 * The installation's Plex client id on iOS: made once (a random UUID) and kept in the app's user defaults, the iOS
 * counterpart of the SharedPreferences entry Android uses. It is not a secret (Plex shows it as the device's id).
 */
class IosPlexClientIdentifier(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : PlexClientIdentifier {

    override val value: String by lazy {
        defaults.stringForKey(KEY_CLIENT_ID)
            ?: NSUUID().UUIDString().lowercase().also { id -> defaults.setObject(id, forKey = KEY_CLIENT_ID) }
    }

    private companion object {
        const val KEY_CLIENT_ID = "plex.client_identifier"
    }
}
