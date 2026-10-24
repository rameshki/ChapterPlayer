package com.chapterplayer.testing

import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** An in-memory [AppSettings] for view model tests. The theme, layout, car resume and Downloaded only are writable. */
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
    val carResume = MutableStateFlow(true)
    override val resumeOnCarConnect: Flow<Boolean> = carResume
    override suspend fun setResumeOnCarConnect(enabled: Boolean) { carResume.value = enabled }
    val downloadedOnlyFlow = MutableStateFlow(false)
    override val downloadedOnly: Flow<Boolean> = downloadedOnlyFlow
    override suspend fun setDownloadedOnly(enabled: Boolean) { downloadedOnlyFlow.value = enabled }
    val speed = MutableStateFlow(1.0f)
    override val playbackSpeed: Flow<Float> = speed
    override suspend fun setPlaybackSpeed(speed: Float) { this.speed.value = speed }
}
