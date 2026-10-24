package com.chapterplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.downloads.STORAGE_LIMIT_OPTIONS
import com.chapterplayer.domain.downloads.storageLimitLabel
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/** Settings, Downloads: where new downloads go. Each volume with its free space. */
@Composable
internal fun DownloadLocationCard(locations: DownloadLocationsUi, onSelect: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceElevated), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                text = "Download location",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = "Only new downloads go here. Books already downloaded stay where they are.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Column(modifier = Modifier.selectableGroup().padding(vertical = 4.dp)) {
                locations.options.forEach { option ->
                    val selected = option.id == locations.selectedId
                    RadioLine(
                        label = option.label,
                        detail = option.detail,
                        selected = selected,
                        onClick = { onSelect(option.id) }
                    )
                }
            }
        }
    }
}

/** Settings, Downloads: the storage limit, chosen in a dialog. */
@Composable
internal fun StorageLimitCard(limitBytes: Long?, onSelect: (Long?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceElevated), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "Change storage limit") { choosing = true }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("Storage limit", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = storageLimitLabel(limitBytes),
                style = MaterialTheme.typography.bodySmall,
                color = AmberGold
            )
            Text(
                text = "A download that would go over the limit does not start.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = SurfaceElevated,
            title = { Text("Storage limit") },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    STORAGE_LIMIT_OPTIONS.forEach { option ->
                        RadioLine(
                            label = storageLimitLabel(option),
                            detail = null,
                            selected = option == limitBytes,
                            onClick = {
                                onSelect(option)
                                choosing = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) { Text("Close", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun RadioLine(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AmberGold))
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else TextSecondary
            )
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}
