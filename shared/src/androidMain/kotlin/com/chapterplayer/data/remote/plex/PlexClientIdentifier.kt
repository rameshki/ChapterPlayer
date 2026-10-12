package com.chapterplayer.data.remote.plex

import android.content.Context
import java.util.UUID

/**
 * Stable X-Plex-Client-Identifier for this install.
 *
 * Plex treats each distinct client identifier as a separate device on the user's
 * account, so it must be generated once and reused for every plex.tv request.
 */
class PlexClientIdentifier(
    context: Context
) {
    val value: String by lazy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_CLIENT_ID, null)
            ?: UUID.randomUUID().toString().also { id ->
                prefs.edit().putString(KEY_CLIENT_ID, id).apply()
            }
    }

    private companion object {
        const val PREFS_NAME = "plex"
        const val KEY_CLIENT_ID = "client_identifier"
    }
}
