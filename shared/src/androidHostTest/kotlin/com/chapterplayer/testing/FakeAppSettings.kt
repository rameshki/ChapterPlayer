package com.chapterplayer.testing

import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** An in-memory [AppSettings] for view model tests. Only the theme is writable. */
class FakeAppSettings : AppSettings {
    val theme = MutableStateFlow(ThemeMode.SYSTEM)
    override val themeMode: Flow<ThemeMode> = theme
    override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
    override val libraryLayout: Flow<LibraryLayout> = flowOf(LibraryLayout.LARGE_GRID)
    override suspend fun setLibraryLayout(layout: LibraryLayout) {}
    override val resumeOnCarConnect: Flow<Boolean> = flowOf(true)
    override suspend fun setResumeOnCarConnect(enabled: Boolean) {}
}
