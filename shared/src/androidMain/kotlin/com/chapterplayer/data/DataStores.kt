package com.chapterplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile

/**
 * One DataStore file per purpose, so the encrypted credentials stay apart from ordinary settings.
 * A damaged file is reset to empty, so reads fall back to defaults and writes work again instead of throwing.
 */
fun createPreferencesDataStore(context: Context, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile(name) },
    )
