package com.chapterplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.FilterChip
import com.chapterplayer.domain.browse.offeredChips
import com.chapterplayer.ui.components.LocalPlatformFeatures
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextSecondary

internal fun FilterChip.label(): String = when (this) {
    FilterChip.ALL -> "All"
    FilterChip.DOWNLOADED -> "Downloaded"
    FilterChip.IN_PROGRESS -> "In progress"
    FilterChip.NOT_STARTED -> "Not started"
    FilterChip.FINISHED -> "Finished"
}

/**
 * The quick filters (single choice) and the button that opens the Filter sheet. [selected] is null
 * when the sheet made a combination no chip stands for, so no chip is highlighted. [serverChip] is the
 * label of the active server filter: its chip opens the sheet, its cross clears it.
 */
@Composable
fun FilterChipRow(
    selected: FilterChip?,
    onSelected: (FilterChip) -> Unit,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
    serverChip: String? = null,
    onClearServers: () -> Unit = {}
) {
    // No Downloaded chip on a platform without downloads (iOS for now).
    val chips = offeredChips(LocalPlatformFeatures.current.downloads)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f).selectableGroup(),
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (serverChip != null) {
                item(key = "server") { ServerChip(serverChip, onOpen = onOpenSheet, onClear = onClearServers) }
            }
            items(chips, key = { it.name }) { chip ->
                ChipItem(label = chip.label(), selected = chip == selected, onClick = { onSelected(chip) })
            }
        }
        IconButton(onClick = onOpenSheet) {
            Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = TextSecondary)
        }
    }
}

/** A pill inside a 48dp touch target; one option of a single-choice group. */
@Composable
private fun ChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected) AmberGold else SurfaceSecondary)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** The active server filter: the label opens the Filter sheet and the cross shows every server again. */
@Composable
private fun ServerChip(label: String, onOpen: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AmberGold),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .clickable(onClickLabel = "Choose servers", onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClick = onClear),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Clear server filter",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
