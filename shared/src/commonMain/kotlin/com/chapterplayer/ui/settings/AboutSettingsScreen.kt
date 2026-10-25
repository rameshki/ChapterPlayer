package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.AppInfo
import com.chapterplayer.domain.settings.aboutSummary
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.koinInject

/** Settings, About: the app name, its version and where the code lives. */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit, appInfo: AppInfo = koinInject()) {
    SettingsPage(title = "About", onBack = onBack) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ChapterPlayer",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = aboutSummary(appInfo.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "ChapterPlayer is open source. The code lives in the ChapterPlayer repository on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
