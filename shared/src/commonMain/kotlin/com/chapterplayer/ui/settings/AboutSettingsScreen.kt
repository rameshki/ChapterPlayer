package com.chapterplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.diagnostics.ProblemReporter
import com.chapterplayer.di.DebuggableQualifier
import com.chapterplayer.domain.diagnostics.AboutEntry
import com.chapterplayer.domain.diagnostics.aboutEntries
import com.chapterplayer.domain.settings.aboutSummary
import com.chapterplayer.ui.components.LocalTextClipboard
import com.chapterplayer.ui.components.LocalTextSharer
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val REPORT_TITLE = "ChapterPlayer problem report"

/** Settings, About: the app name, its version and where the code lives. */
@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    appInfo: AppInfo = koinInject(),
    debuggable: Boolean = koinInject(DebuggableQualifier),
    reporter: ProblemReporter = koinInject(),
) {
    val sharer = LocalTextSharer.current
    val clipboard = LocalTextClipboard.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
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
                // A long press opens the book-level Diagnostics page in any build.
                Text(
                    text = aboutSummary(appInfo.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .combinedClickable(onClick = {}, onLongClickLabel = "Open diagnostics", onLongClick = onOpenDiagnostics)
                )
                Text(
                    text = "ChapterPlayer is open source. The code lives in the ChapterPlayer repository on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, enabled = !busy) {
                        busy = true
                        scope.launch {
                            val report = try { withContext(Dispatchers.Default) { reporter.build() } } finally { busy = false }
                            if (sharer != null) {
                                sharer.share(REPORT_TITLE, report)
                                note = null
                            } else {
                                clipboard.copy(report)
                                note = "Report copied. Paste it where you want to send it."
                            }
                        }
                    }
                    .padding(16.dp)
            ) {
                Text(
                    text = "Report a problem",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Shares a text report: app and phone version, your servers by kind, the last sync, the last crash and recent events. Addresses, names and passwords are removed, and nothing is sent unless you choose where.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                note?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        // Developer tools: only in a debuggable build, never in a release build.
        if (AboutEntry.DIAGNOSTICS in aboutEntries(debuggable)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenDiagnostics)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "What this phone and each server hold for a book (debug builds only).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
