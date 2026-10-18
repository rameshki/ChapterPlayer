package com.chapterplayer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.showOfflineBanner
import com.chapterplayer.ui.theme.SurfaceElevated

/**
 * Shown above the Library when the device is offline or "Downloaded only" is on: says what still
 * plays and lets the user choose "Downloaded only". Draws nothing otherwise.
 */
@Composable
fun OfflineBanner(
    downloadedOnly: Boolean,
    online: Boolean,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showOfflineBanner(online, downloadedOnly)) return
    Surface(modifier = modifier.fillMaxWidth(), color = SurfaceElevated) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = if (online) "Downloaded only is on. Only downloaded books can play."
                else "You're offline. Only downloaded books can play.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .toggleable(
                        value = downloadedOnly,
                        role = Role.Switch,
                        onValueChange = onDownloadedOnlyChange
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Downloaded only",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // The row is the single touch target; the Switch itself is not clickable.
                Switch(checked = downloadedOnly, onCheckedChange = null)
            }
        }
    }
}
