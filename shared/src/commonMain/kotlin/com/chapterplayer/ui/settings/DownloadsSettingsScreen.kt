package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.downloads.DOWNLOADS_NOT_ON_IOS_TEXT
import com.chapterplayer.domain.downloads.formatBytes
import com.chapterplayer.ui.components.LocalPlatformFeatures
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Downloads and offline: network, location, storage, the Downloaded only switch and the several-servers choice. */
@Composable
fun DownloadsSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val downloadedOnly by viewModel.downloadedOnly.collectAsStateWithLifecycle()
    val downloadWifiOnly by viewModel.downloadWifiOnly.collectAsStateWithLifecycle()
    val downloadLocations by viewModel.downloadLocations.collectAsStateWithLifecycle()
    val downloadStorageLimit by viewModel.downloadStorageLimit.collectAsStateWithLifecycle()
    val storageUsed by viewModel.downloadStorageUsed.collectAsStateWithLifecycle()
    val sourcePreference by viewModel.sourcePreference.collectAsStateWithLifecycle()
    val mirrorProgress by viewModel.mirrorProgress.collectAsStateWithLifecycle()
    // A card may have been put in or taken out since the screen was last open.
    LaunchedEffect(Unit) { viewModel.refreshDownloadLocations() }

    val features = LocalPlatformFeatures.current
    SettingsPage(title = "Downloads and offline", onBack = onBack) {
        if (features.downloads) {
            DownloadOptions(viewModel, downloadWifiOnly, downloadLocations, downloadStorageLimit, storageUsed, downloadedOnly)
        } else {
            // iOS until Stage 5 step 5: no downloader, so only the several-servers choices below apply.
            SectionTitle("Downloads")
            Text(
                text = DOWNLOADS_NOT_ON_IOS_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
        }

        SectionTitle("Books on several servers")
        SourcePreferenceCard(sourcePreference, viewModel::setSourcePreference)
        Spacer(Modifier.height(8.dp))
        SwitchCard(
            title = "Keep progress in sync between copies",
            description = "Listening to one copy moves the others to the same place, on this phone and on their servers.",
            checked = mirrorProgress,
            onChange = viewModel::setMirrorProgress
        )
    }
}

/** Network, location, storage and the Downloaded only switch: the parts that need a downloader. */
@Composable
private fun DownloadOptions(
    viewModel: SettingsViewModel,
    downloadWifiOnly: Boolean,
    downloadLocations: DownloadLocationsUi,
    downloadStorageLimit: Long?,
    storageUsed: Long,
    downloadedOnly: Boolean,
) {
    SectionTitle("Network")
    SwitchCard(
        title = "Download on Wi-Fi only",
        description = "Downloads wait for Wi-Fi. Turn off to use mobile data too.",
        checked = downloadWifiOnly,
        onChange = viewModel::setDownloadWifiOnly
    )
    Spacer(Modifier.height(16.dp))

    SectionTitle("Where files go")
    DownloadLocationCard(downloadLocations, viewModel::setDownloadLocation)
    Spacer(Modifier.height(16.dp))

    SectionTitle("Storage")
    StorageLimitCard(downloadStorageLimit, viewModel::setDownloadStorageLimit)
    Text(
        text = "Used by downloads: ${formatBytes(storageUsed)}",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Spacer(Modifier.height(8.dp))

    SectionTitle("Offline")
    SwitchCard(
        title = "Downloaded only",
        description = "Only downloaded books can play, even when online.",
        checked = downloadedOnly,
        onChange = viewModel::setDownloadedOnly
    )
    Spacer(Modifier.height(16.dp))
}
