package com.chapterplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary

/**
 * A one-line note that the Library's server filter also hides books here (Home, Search), with a button
 * that shows every server again. The filter itself is chosen in the Library filter sheet.
 */
@Composable
fun ServerFilterHint(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.FilterList, contentDescription = null, tint = TextSecondary)
        Text(
            text = "Server filter on",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) { Text("Show all servers", color = AmberGold) }
    }
}
