package com.chapterplayer.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.settings.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the device has a network and whether the user chose "Downloaded only". */
data class OfflineUiState(val online: Boolean = true, val downloadedOnly: Boolean = false)

class OfflineStateViewModel(
    connectivity: ConnectivityMonitor,
    private val settings: AppSettings
) : ViewModel() {

    val uiState: StateFlow<OfflineUiState> = combine(
        connectivity.isOnline,
        settings.downloadedOnly
    ) { online, downloadedOnly -> OfflineUiState(online, downloadedOnly) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineUiState())

    fun setDownloadedOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setDownloadedOnly(enabled) }
    }
}
