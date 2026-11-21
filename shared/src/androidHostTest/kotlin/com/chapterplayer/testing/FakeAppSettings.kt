package com.chapterplayer.testing

import com.chapterplayer.data.settings.AppFont
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import com.chapterplayer.domain.browse.SourceChoice
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
    val font = MutableStateFlow(AppFont.CLASSIC)
    override val appFont: Flow<AppFont> = font
    override suspend fun setAppFont(font: AppFont) {
        if (failOnSet) error("storage unavailable")
        this.font.value = font
    }
    val layout = MutableStateFlow(LibraryLayout.LARGE_GRID)
    override val libraryLayout: Flow<LibraryLayout> = layout
    override suspend fun setLibraryLayout(layout: LibraryLayout) { this.layout.value = layout }
    val carResume = MutableStateFlow(true)
    override val resumeOnCarConnect: Flow<Boolean> = carResume
    override suspend fun setResumeOnCarConnect(enabled: Boolean) { carResume.value = enabled }
    val batteryShown = MutableStateFlow(false)
    override val batteryPromptShown: Flow<Boolean> = batteryShown
    override suspend fun setBatteryPromptShown() { batteryShown.value = true }
    val lastSeen = MutableStateFlow<String?>(null)
    override val lastSeenVersion: Flow<String?> = lastSeen
    override suspend fun setLastSeenVersion(version: String) { lastSeen.value = version }
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
    val skipBack = MutableStateFlow(15)
    override val skipBackSeconds: Flow<Int> = skipBack
    override suspend fun setSkipBackSeconds(seconds: Int) { skipBack.value = seconds }
    val skipForward = MutableStateFlow(30)
    override val skipForwardSeconds: Flow<Int> = skipForward
    override suspend fun setSkipForwardSeconds(seconds: Int) { skipForward.value = seconds }
    val bookSpeeds = mutableMapOf<String, Float>()
    override suspend fun bookSpeed(bookId: String): Float? = bookSpeeds[bookId]
    override suspend fun setBookSpeed(bookId: String, speed: Float) { bookSpeeds[bookId] = speed }
    val downloadChoiceFlow = MutableStateFlow<SourceChoice>(SourceChoice.Ask)
    override val downloadSourceChoice: Flow<SourceChoice> = downloadChoiceFlow
    override suspend fun setDownloadSourceChoice(choice: SourceChoice) {
        if (failOnSet) error("storage unavailable")
        downloadChoiceFlow.value = choice
    }
    val playChoiceFlow = MutableStateFlow<SourceChoice>(SourceChoice.Ask)
    override val playSourceChoice: Flow<SourceChoice> = playChoiceFlow
    override suspend fun setPlaySourceChoice(choice: SourceChoice) {
        if (failOnSet) error("storage unavailable")
        playChoiceFlow.value = choice
    }
    val crossSyncOnAddFlow = MutableStateFlow(false)
    override val crossSyncOnAdd: Flow<Boolean> = crossSyncOnAddFlow
    override suspend fun setCrossSyncOnAdd(enabled: Boolean) {
        if (failOnSet) throw IllegalStateException("disk full")
        crossSyncOnAddFlow.value = enabled
    }
    val mirrorProgressFlow = MutableStateFlow(true)
    override val mirrorProgress: Flow<Boolean> = mirrorProgressFlow
    override suspend fun setMirrorProgress(enabled: Boolean) {
        if (failOnSet) error("storage unavailable")
        mirrorProgressFlow.value = enabled
    }
    val browseFiltersFlow = MutableStateFlow<String?>(null)
    override val browseFilters: Flow<String?> = browseFiltersFlow
    override suspend fun setBrowseFilters(encoded: String) {
        if (failOnSet) error("storage unavailable")
        browseFiltersFlow.value = encoded
    }
    val libraryViewFlow = MutableStateFlow<String?>(null)
    override val libraryView: Flow<String?> = libraryViewFlow
    override suspend fun setLibraryView(name: String) {
        if (failOnSet) error("storage unavailable")
        libraryViewFlow.value = name
    }
}
