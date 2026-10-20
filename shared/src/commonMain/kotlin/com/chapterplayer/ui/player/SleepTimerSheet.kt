package com.chapterplayer.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.playback.SLEEP_PRESETS
import com.chapterplayer.domain.playback.SleepTimer
import com.chapterplayer.domain.playback.sleepPresetLabel
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

/** Sleep timer presets. A running timer shows its countdown on top; a tap sets a preset and closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    current: SleepTimer,
    countdownLabel: String,
    onTimerSelected: (SleepTimer) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Sleep timer",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            if (current != SleepTimer.Off) {
                Text(
                    text = countdownLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberGold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Column(modifier = Modifier.selectableGroup().padding(top = 8.dp)) {
                SLEEP_PRESETS.forEach { preset ->
                    val selected = preset == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                onTimerSelected(preset)
                                onDismiss()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = AmberGold, unselectedColor = TextSecondary)
                        )
                        Text(
                            text = sleepPresetLabel(preset),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
