package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.domain.playback.SKIP_BACK_CHOICES_SEC
import com.chapterplayer.domain.playback.SKIP_FORWARD_CHOICES_SEC
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Playback: how far the back and forward buttons jump, on every surface (player, notification, widgets, car, headset). */
@Composable
fun PlaybackSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val back by viewModel.skipBackSeconds.collectAsStateWithLifecycle()
    val forward by viewModel.skipForwardSeconds.collectAsStateWithLifecycle()
    PlaybackSettingsContent(back, forward, viewModel::setSkipBackSeconds, viewModel::setSkipForwardSeconds, onBack)
}

/** The page's body, apart from the view model so a screenshot test can draw it. */
@Composable
internal fun PlaybackSettingsContent(
    backSec: Int,
    forwardSec: Int,
    onSelectBack: (Int) -> Unit,
    onSelectForward: (Int) -> Unit,
    onNavigateUp: () -> Unit,
) {
    SettingsPage(title = "Playback", onBack = onNavigateUp) {
        SectionTitle("Skip back")
        SecondsChoiceCard(SKIP_BACK_CHOICES_SEC, backSec, onSelectBack)
        Spacer(Modifier.height(16.dp))
        SectionTitle("Skip forward")
        SecondsChoiceCard(SKIP_FORWARD_CHOICES_SEC, forwardSec, onSelectForward)
        Text(
            "Used by the player, the notification, the widgets, the car and the headset buttons.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp)
        )
    }
}

/** One radio row per length, e.g. "15 seconds". */
@Composable
private fun SecondsChoiceCard(choices: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceElevated), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.selectableGroup()) {
            choices.forEach { seconds ->
                val isSelected = seconds == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(seconds) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AmberGold))
                    Text(
                        text = "$seconds seconds",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else TextSecondary,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}
