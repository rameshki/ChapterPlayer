package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.whatsnew.NoteLine
import com.chapterplayer.domain.whatsnew.ReleaseNotes
import com.chapterplayer.domain.whatsnew.notesToShow
import com.chapterplayer.domain.whatsnew.parseChangelog
import com.chapterplayer.domain.whatsnew.releaseVersion
import com.chapterplayer.domain.whatsnew.splitBold
import com.chapterplayer.resources.Res
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Shows what changed in the version just installed, once (review R28). The text is the bundled copy of
 * `CHANGELOG.md`. A fresh install is not an update, so it only records the version and Home shows the first-run
 * guidance instead. Closing the sheet records the version, so it does not come back until the next update.
 */
@Composable
fun WhatsNewPrompt(
    appInfo: AppInfo = koinInject(),
    settings: AppSettings = koinInject(),
    servers: ServerRepository = koinInject(),
) {
    val current = remember { releaseVersion(appInfo.versionName) }
    var notes by remember { mutableStateOf<ReleaseNotes?>(null) }
    LaunchedEffect(current) {
        try {
            val lastSeen = settings.lastSeenVersion.first()
            if (lastSeen == current) return@LaunchedEffect
            val changelog = parseChangelog(Res.readBytes("files/CHANGELOG.md").decodeToString())
            val hasServers = servers.observeAll().first().isNotEmpty()
            val toShow = notesToShow(lastSeen, current, hasServers, changelog)
            // A fresh install, or a version with no notes, has nothing to show: remember the version at once.
            if (toShow == null) settings.setLastSeenVersion(current) else notes = toShow
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No notes is better than a failed start: the sheet simply does not appear.
        }
    }
    val shown = notes ?: return
    val scope = rememberCoroutineScope()
    val close = {
        notes = null
        scope.launch {
            try { settings.setLastSeenVersion(current) } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        Unit
    }
    ModalBottomSheet(onDismissRequest = { close() }) { WhatsNewContent(shown, onClose = close) }
}

/** The sheet's body: the title, the notes, and the close button (a screenshot test can draw it). */
@Composable
internal fun WhatsNewContent(notes: ReleaseNotes, onClose: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        Text(
            "What's new in ${notes.version}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        notes.date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            notes.lines.forEach { line ->
                when (line) {
                    is NoteLine.Heading -> Text(
                        line.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = AmberGold,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
                    )
                    is NoteLine.Bullet -> Text(boldText("•  " + line.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    is NoteLine.Plain -> Text(boldText(line.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = MaterialTheme.colorScheme.onPrimary)
        ) { Text("Got it") }
    }
}

private fun boldText(text: String): AnnotatedString = buildAnnotatedString {
    splitBold(text).forEach { run ->
        if (run.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run.text) } else append(run.text)
    }
}
