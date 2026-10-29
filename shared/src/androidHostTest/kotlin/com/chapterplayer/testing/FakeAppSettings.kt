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
    val wifiOnly = MutableStateFlow(true)
    override val downloadWifiOnly: Flow<Boolean> = wifiOnly
    override suspend fun setDownloadWifiOnly(enabled: Boolean) {
        if (failOnSet) error("storage unavailable")
        wifiOnly.value = enabled
    }
    val storageLimit = MutableStateFlow<Long?>(null)
    override val downloadStorageLimitBytes: Flow<Long?> = storageLimit
    override suspend fun setDownloadStorageLimitBytes(limitBytes: Long?) {
        if (failOnSet) error("storage unavailable")
        storageLimit.value = limitBytes
    }
    val locationId = MutableStateFlow<String?>(null)
    override val downloadLocationId: Flow<String?> = locationId
    override suspend fun setDownloadLocationId(id: String?) {
        if (failOnSet) error("storage unavailable")
        locationId.value = id
    }
    val speed = MutableStateFlow(1.0f)
    override val playbackSpeed: Flow<Float> = speed
    override suspend fun setPlaybackSpeed(speed: Float) { this.speed.value = speed }
    val sourcePreferenceFlow = MutableStateFlow(com.chapterplayer.domain.browse.DEFAULT_SOURCE_PREFERENCE)
    override val sourcePreference: Flow<com.chapterplayer.domain.browse.SourcePreference> = sourcePreferenceFlow
    override suspend fun setSourcePreference(preference: com.chapterplayer.domain.browse.SourcePreference) { sourcePreferenceFlow.value = preference }
    val mirrorProgressFlow = MutableStateFlow(true)
    override val mirrorProgress: Flow<Boolean> = mirrorProgressFlow
    override suspend fun setMirrorProgress(enabled: Boolean) {
        if (failOnSet) error("storage unavailable")
        mirrorProgressFlow.value = enabled
    }
}
