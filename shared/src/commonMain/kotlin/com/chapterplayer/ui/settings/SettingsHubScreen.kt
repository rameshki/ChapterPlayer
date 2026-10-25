package com.chapterplayer.ui.settings

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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.AppInfo
import com.chapterplayer.domain.settings.aboutSummary
import com.chapterplayer.domain.settings.appearanceSummary
import com.chapterplayer.domain.settings.carSummary
import com.chapterplayer.domain.settings.downloadsSummary
import com.chapterplayer.domain.settings.rowDescription
import com.chapterplayer.domain.settings.serversSummary
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Settings home: one row per page, each with a live one-line summary. */
@Composable
fun SettingsHubScreen(
    onOpenServers: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCar: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
    appInfo: AppInfo = koinInject()
) {
    val uiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val resumeOnCarConnect by viewModel.resumeOnCarConnect.collectAsStateWithLifecycle()
    val downloadWifiOnly by viewModel.downloadWifiOnly.collectAsStateWithLifecycle()
    val downloadLocations by viewModel.downloadLocations.collectAsStateWithLifecycle()
    val downloadStorageLimit by viewModel.downloadStorageLimit.collectAsStateWithLifecycle()

    val locationLabel = downloadLocations.options.firstOrNull { it.id == downloadLocations.selectedId }?.label

    SettingsPage(title = "Settings", onBack = onBack) {
        HubGroup(null) {
            HubRow(Icons.Default.Dns, "Servers", serversSummary(uiState.servers), onOpenServers)
        }
        HubGroup("Listening") {
            HubRow(Icons.Default.Palette, "Appearance", appearanceSummary(themeMode), onOpenAppearance)
        }
        HubGroup("Offline") {
            HubRow(
                Icons.Default.Download,
                "Downloads and offline",
                downloadsSummary(downloadWifiOnly, locationLabel, downloadStorageLimit),
                onOpenDownloads
            )
        }
        HubGroup("Devices") {
            HubRow(Icons.Default.DirectionsCar, "Android Auto", carSummary(resumeOnCarConnect), onOpenCar)
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
