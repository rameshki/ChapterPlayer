package com.chapterplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.FilterHint
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary

/**
 * A one-line note that the Library's filters also hide books here (Home, Search), with a button that turns them
 * off ([hint]: "Server filter on" / "Filters on"). The filters themselves are chosen in the Library filter sheet.
 */
@Composable
fun FilterHintRow(hint: FilterHint, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.FilterList, contentDescription = null, tint = TextSecondary)
        Text(
            text = hint.text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) { Text(hint.clearLabel, color = AmberGold) }
    }
}

/** What an empty Home or Search says while filters are on: they may be the reason. */
const val FILTERS_HIDING_TEXT = "No books match the current filters. Filters set in the Library may be hiding books here."

/** The empty state's button that turns the filters off. */
@Composable
fun ClearFiltersButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = MaterialTheme.colorScheme.onPrimary)
    ) { Text("Clear filters") }
}
