package com.chapterplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.BrowseQuery
import com.chapterplayer.domain.browse.BrowseSort
import com.chapterplayer.domain.browse.DownloadFilter
import com.chapterplayer.ui.components.LocalPlatformFeatures
import com.chapterplayer.domain.browse.ProgressFilter
import com.chapterplayer.domain.browse.ServerFilterOption
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

internal fun DownloadFilter.label(): String = when (this) {
    DownloadFilter.ALL -> "All"
    DownloadFilter.DOWNLOADED -> "Downloaded"
    DownloadFilter.NOT_DOWNLOADED -> "Not downloaded"
}

internal fun ProgressFilter.label(): String = when (this) {
    ProgressFilter.ALL -> "All"
    ProgressFilter.IN_PROGRESS -> "In progress"
    ProgressFilter.NOT_STARTED -> "Not started"
    ProgressFilter.FINISHED -> "Finished"
}

internal fun BrowseSort.label(): String = when (this) {
    BrowseSort.RECENTLY_ADDED -> "Recently added"
    BrowseSort.RECENTLY_PLAYED -> "Recently played"
    BrowseSort.TITLE -> "Title"
    BrowseSort.AUTHOR -> "Author"
    BrowseSort.LENGTH -> "Length"
}

/**
 * Server (check boxes, only with two or more servers), then Download, Progress and Sort as radio groups.
 * Every change applies at once; Reset clears them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    query: BrowseQuery,
    onDownloadSelected: (DownloadFilter) -> Unit,
    onProgressSelected: (ProgressFilter) -> Unit,
    onSortSelected: (BrowseSort) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    serverOptions: List<ServerFilterOption> = emptyList(),
    onServerToggled: (String) -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceElevated) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Filter",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onReset) { Text("Reset", color = AmberGold) }
            }
            if (serverOptions.isNotEmpty()) ServerGroup(serverOptions, onServerToggled)
            // No downloads on this platform (iOS for now): nothing to filter by.
            if (LocalPlatformFeatures.current.downloads) {
                OptionGroup("Download", DownloadFilter.entries, query.download, DownloadFilter::label, onDownloadSelected)
            }
            OptionGroup("Progress", ProgressFilter.entries, query.progress, ProgressFilter::label, onProgressSelected)
            OptionGroup("Sort", BrowseSort.entries, query.sort, BrowseSort::label, onSortSelected)
        }
    }
}

/** A titled single-choice group; each row is one 48dp radio target. */
@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            RadioRow(label(option), selected = option == selected, onClick = { onSelected(option) })
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The row is the single touch target; the button itself is not clickable.
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = AmberGold, unselectedColor = TextSecondary)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

/** The Server section: one check box row per server with its type and its number of books. */
@Composable
private fun ServerGroup(options: List<ServerFilterOption>, onToggled: (String) -> Unit) {
    Text(
        text = "Server",
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    options.forEach { option ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .toggleable(value = option.selected, role = Role.Checkbox, onValueChange = { onToggled(option.id) }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The row is the single touch target; the box itself is not clickable.
            Checkbox(
                checked = option.selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = AmberGold, uncheckedColor = TextSecondary)
            )
            Text(
                text = option.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).weight(1f)
            )
            Text(
                text = option.typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 8.dp)
            )
            Text(
                text = booksLabel(option.count),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
