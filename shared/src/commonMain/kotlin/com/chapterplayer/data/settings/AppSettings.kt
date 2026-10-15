package com.chapterplayer.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The user's app preferences. Every value has a default, so a new install needs no setup. */
interface AppSettings {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    val libraryLayout: Flow<LibraryLayout>
    suspend fun setLibraryLayout(layout: LibraryLayout)

    /** Whether Android Auto resumes the book when the car connects. */
    val resumeOnCarConnect: Flow<Boolean>
    suspend fun setResumeOnCarConnect(enabled: Boolean)
}

class DataStoreAppSettings(private val store: DataStore<Preferences>) : AppSettings {

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val layout = stringPreferencesKey("library_layout")
        val carResume = booleanPreferencesKey("resume_on_car_connect")
    }

    // A damaged settings file reads as "nothing saved", so the defaults apply.
    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val themeMode: Flow<ThemeMode> = prefs.map { p ->
        p[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    override val libraryLayout: Flow<LibraryLayout> = prefs.map { p ->
        p[Keys.layout]?.let { name -> LibraryLayout.entries.firstOrNull { it.name == name } } ?: LibraryLayout.LARGE_GRID
    }

    override val resumeOnCarConnect: Flow<Boolean> = prefs.map { it[Keys.carResume] ?: true }

    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.theme] = mode.name } }
    override suspend fun setLibraryLayout(layout: LibraryLayout) { store.edit { it[Keys.layout] = layout.name } }
    override suspend fun setResumeOnCarConnect(enabled: Boolean) { store.edit { it[Keys.carResume] = enabled } }
}
