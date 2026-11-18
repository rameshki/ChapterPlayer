package com.chapterplayer.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.permissions.BatterySetting
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.settings.BATTERY_TITLE
import com.chapterplayer.domain.settings.shouldAskAboutBattery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The one-time question about running in the background overnight (review R36): when sound is first playing on a phone
 * that limits the app, offer to open the system's battery screen. Either answer is remembered, so it never comes back;
 * the Settings row stays for later.
 */
@Composable
fun BatteryPrompt(isPlaying: Boolean, battery: BatterySetting = koinInject(), settings: AppSettings = koinInject()) {
    // Starts as "shown" so nothing flashes before the saved answer is read.
    val shown by settings.batteryPromptShown.collectAsStateWithLifecycle(initialValue = true)
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaying, shown) {
        if (shouldAskAboutBattery(battery.available, battery.isUnrestricted(), shown, isPlaying)) open = true
    }
    if (!open) return
    val scope = rememberCoroutineScope()
    val close = {
        open = false
        scope.launch {
            try { settings.setBatteryPromptShown() } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        Unit
    }
    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(BATTERY_TITLE) },
        text = {
            Text(
                "Android may stop playback while the phone sits idle at night. In the list that opens, pick " +
                    "ChapterPlayer and choose Unrestricted (or Don't optimise) so a long book keeps playing. " +
                    "You can change this later in Settings."
            )
        },
        confirmButton = { TextButton(onClick = { close(); battery.openSettings() }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { close() }) { Text("Not now") } },
    )
}
