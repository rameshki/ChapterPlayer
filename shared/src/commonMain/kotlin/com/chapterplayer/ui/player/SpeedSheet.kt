package com.chapterplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.playback.PLAYBACK_SPEEDS
import com.chapterplayer.domain.playback.speedLabel
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextSecondary
import kotlin.math.abs

private val QUICK_SPEEDS = listOf(1.0f, 1.25f, 1.5f, 2.0f)

/** Playback speed: quick presets, then every step. A tap applies at once and the sheet stays open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
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
                text = "Playback speed",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            SpeedChips("Quick", QUICK_SPEEDS, currentSpeed, onSpeedSelected)
            SpeedChips("All speeds", PLAYBACK_SPEEDS, currentSpeed, onSpeedSelected)
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).padding(top = 8.dp)
            ) { Text("Done", color = AmberGold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.SpeedChips(title: String, speeds: List<Float>, current: Float, onSelected: (Float) -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        speeds.forEach { speed ->
            val selected = abs(speed - current) < 0.001f
            FilterChip(
                selected = selected,
                onClick = { onSelected(speed) },
                label = { Text(speedLabel(speed), style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = SurfaceSecondary,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = AmberGold,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}
