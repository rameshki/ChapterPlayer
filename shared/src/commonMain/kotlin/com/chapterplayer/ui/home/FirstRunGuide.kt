package com.chapterplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary

/** The three steps a first launch explains (review R28): which servers, which libraries, and the widget. */
internal val FIRST_RUN_STEPS = listOf(
    "Add a server" to "Audiobookshelf, Jellyfin or Plex. For Jellyfin, the server needs a library of type Books.",
    "Pick your libraries" to "After signing in, choose which libraries to include. You can change this in Settings, Servers.",
    "Add a widget" to "Optional: press and hold the home screen, choose Widgets, then ChapterPlayer, to resume with one tap.",
)

/** What Home shows before any server exists: a welcome, the steps, and the button that starts the first one. */
@Composable
internal fun FirstRunGuide(onOpenServers: () -> Unit, showWidgetStep: Boolean = true) {
    val steps = if (showWidgetStep) FIRST_RUN_STEPS else FIRST_RUN_STEPS.dropLast(1)
    Column(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp), tint = TextSecondary)
        Text(
            "Welcome to ChapterPlayer",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            steps.forEachIndexed { index, (title, detail) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = AmberGold)
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenServers,
            modifier = Modifier.heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text("Add a server") }
    }
}
