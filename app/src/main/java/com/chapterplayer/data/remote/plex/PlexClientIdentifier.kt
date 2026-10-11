package com.chapterplayer.data.remote.plex

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable X-Plex-Client-Identifier for this install.
 *
 * Plex treats each distinct client identifier as a separate device on the user's
 * account, so it must be generated once and reused for every plex.tv request.
 */
@Singleton
class PlexClientIdentifier @Inject constructor(
    @ApplicationContext context: Context
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
