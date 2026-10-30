package com.chapterplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import okio.Path.Companion.toPath

/**
 * Where the app keeps its files on this platform: absolute paths, in a folder that exists. iOS uses the app's
 * Application Support folder (`IosPathProvider`). Android keeps its DataStore files and database where they always were
 * (`createPreferencesDataStore(context, name)` and Room's own database folder), so installed apps find their data.
 */
interface PathProvider {
    /** The file of the DataStore called [name] (e.g. "settings"). */
    fun preferencesFile(name: String): String

    /** The Room database file called [name]. */
    fun databaseFile(name: String): String
}

/**
 * One DataStore file per purpose, at the path [producePath] gives. A damaged file is reset to empty, so reads fall back
 * to defaults and writes work again instead of throwing (the same rule as Android's factory).
 */
fun createPreferencesDataStore(producePath: () -> String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { producePath().toPath() },
    )
