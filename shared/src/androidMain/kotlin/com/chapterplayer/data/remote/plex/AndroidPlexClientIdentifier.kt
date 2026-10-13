package com.chapterplayer.data.remote.plex

import android.content.Context
import java.util.UUID

/**
 * Keeps the identifier in the same SharedPreferences entry earlier versions used, so an
 * existing installation keeps the id Plex already knows.
 */
class AndroidPlexClientIdentifier(context: Context) : PlexClientIdentifier {

    override val value: String by lazy {
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
