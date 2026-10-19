package com.chapterplayer.testing

import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** An in-memory [AppSettings] for view model tests. The theme, layout and Downloaded only are writable. */
class FakeAppSettings(
    initialTheme: ThemeMode = ThemeMode.SYSTEM,
    private val failOnSet: Boolean = false
) : AppSettings {
    val theme = MutableStateFlow(initialTheme)
    override val themeMode: Flow<ThemeMode> = theme
    override suspend fun setThemeMode(mode: ThemeMode) {
        if (failOnSet) error("storage unavailable")
        theme.value = mode
    }
    val layout = MutableStateFlow(LibraryLayout.LARGE_GRID)
    override val libraryLayout: Flow<LibraryLayout> = layout
    override suspend fun setLibraryLayout(layout: LibraryLayout) { this.layout.value = layout }
    override val resumeOnCarConnect: Flow<Boolean> = flowOf(true)
    override suspend fun setResumeOnCarConnect(enabled: Boolean) {}
    val downloadedOnlyFlow = MutableStateFlow(false)
    override val downloadedOnly: Flow<Boolean> = downloadedOnlyFlow
    override suspend fun setDownloadedOnly(enabled: Boolean) { downloadedOnlyFlow.value = enabled }
}
