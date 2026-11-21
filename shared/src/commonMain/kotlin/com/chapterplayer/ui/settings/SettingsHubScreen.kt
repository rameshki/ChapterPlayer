package com.chapterplayer.ui.settings

import com.chapterplayer.domain.settings.BACKUP_SUMMARY
import com.chapterplayer.domain.playback.skipSummary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.permissions.BatterySetting
import com.chapterplayer.domain.settings.BATTERY_TITLE
import com.chapterplayer.domain.settings.aboutSummary
import com.chapterplayer.domain.settings.batterySummary
import com.chapterplayer.domain.settings.appearanceSummary
import com.chapterplayer.domain.settings.carSummary
import com.chapterplayer.domain.settings.downloadLocationLabel
import com.chapterplayer.domain.settings.downloadsSummary
import com.chapterplayer.domain.settings.rowDescription
import com.chapterplayer.domain.settings.serversSummary
import com.chapterplayer.domain.stats.STATS_SUMMARY
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import com.chapterplayer.ui.components.LocalPlatformFeatures
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Settings home: one row per page, each with a live one-line summary. */
@Composable
fun SettingsHubScreen(
    onOpenServers: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCar: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    appInfo: AppInfo = koinInject(),
    battery: BatterySetting = koinInject()
) {
    val uiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appFont by viewModel.appFont.collectAsStateWithLifecycle()
    val resumeOnCarConnect by viewModel.resumeOnCarConnect.collectAsStateWithLifecycle()
    val downloadWifiOnly by viewModel.downloadWifiOnly.collectAsStateWithLifecycle()
    val downloadLocations by viewModel.downloadLocations.collectAsStateWithLifecycle()
    val downloadStorageLimit by viewModel.downloadStorageLimit.collectAsStateWithLifecycle()
    val sourceChoices by viewModel.sourceChoiceCards.collectAsStateWithLifecycle()
    val skipBack by viewModel.skipBackSeconds.collectAsStateWithLifecycle()
    val skipForward by viewModel.skipForwardSeconds.collectAsStateWithLifecycle()

    // A card may have been put in or taken out since the hub was last open.
    LaunchedEffect(Unit) { viewModel.refreshDownloadLocations() }
    // Read again whenever the app comes back to the front, because the person changes it in the system's own screen.
    var batteryUnrestricted by remember { mutableStateOf(battery.isUnrestricted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryUnrestricted = battery.isUnrestricted() }
    // iOS has no downloader and no car yet: the Downloads row says so, and there is no Android Auto row.
    val features = LocalPlatformFeatures.current
    val locationLabel = downloadLocationLabel(downloadLocations.chosenId, downloadLocations.options.associate { it.id to it.label })

    SettingsPage(title = "Settings", onBack = onBack) {
        HubGroup(null) {
            HubRow(Icons.Default.Dns, "Servers", serversSummary(uiState.servers), onOpenServers)
        }
        HubGroup("Listening") {
            HubRow(Icons.Default.Palette, "Appearance", appearanceSummary(themeMode, appFont), onOpenAppearance)
            HubRow(Icons.Default.Speed, "Playback", skipSummary(skipBack, skipForward), onOpenPlayback)
            HubRow(Icons.Default.BarChart, "Statistics", STATS_SUMMARY, onOpenStats)
            if (battery.available) {
                HubRow(Icons.Default.BatteryChargingFull, BATTERY_TITLE, batterySummary(batteryUnrestricted), battery::openSettings)
            }
        }
        HubGroup("Offline") {
            HubRow(
                Icons.Default.Download,
                "Downloads and offline",
                downloadsSummary(downloadWifiOnly, locationLabel, downloadStorageLimit, available = features.downloads, choices = sourceChoices.summary(features.downloads)),
                onOpenDownloads
            )
        }
        if (features.car) {
            HubGroup("Devices") {
                HubRow(Icons.Default.DirectionsCar, "Android Auto", carSummary(resumeOnCarConnect), onOpenCar)
            }
        }
        HubGroup("Your data") {
            HubRow(Icons.Default.Backup, "Back up and restore", BACKUP_SUMMARY, onOpenBackup)
        }
        HubGroup("About") {
            HubRow(Icons.Default.Info, "About", aboutSummary(appInfo.versionName), onOpenAbout)
        }
    }
}

/** A small uppercase label over a card holding rows. */
@Composable
private fun HubGroup(label: String?, rows: @Composable () -> Unit) {
    if (label != null) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp)
        )
    } else {
        Spacer(Modifier.height(8.dp))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { rows() }
    }
}

/** One page entry. It is a single button; a screen reader hears the title and the summary together. */
@Composable
private fun HubRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    val description = rowDescription(title, summary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AmberGold, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
    }
}
