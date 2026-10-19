package com.chapterplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.ui.offline.OfflineStateViewModel
import org.koin.compose.viewmodel.koinViewModel

/** The offline banner wired to the device state; draws nothing while online. Put it at the top of a browse screen. */
@Composable
fun OfflineHeader(
    modifier: Modifier = Modifier,
    viewModel: OfflineStateViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OfflineBanner(
        downloadedOnly = state.downloadedOnly,
        online = state.online,
        onDownloadedOnlyChange = viewModel::setDownloadedOnly,
        modifier = modifier
    )
}
