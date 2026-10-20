package com.chapterplayer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import android.content.Intent
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.domain.playback.EXTRA_OPEN_PLAYER
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.navigation.AppNavigation
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import com.chapterplayer.ui.theme.DarkPalette
import com.chapterplayer.ui.theme.LightPalette
import com.chapterplayer.ui.theme.resolveDarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Single-activity entry point for the app.
 * Navigation between screens is handled entirely by Compose Navigation.
 */
class MainActivity : ComponentActivity() {

    private val settings: AppSettings by inject()
    private val openPlayerRequest: OpenPlayerRequest by inject()

    // The answer is not used: playback works without notifications, the person can allow them in Settings.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForNotificationsOnce()
        handleOpenPlayer(intent)
        // Read the saved choice before the first frame so a dark-theme user never sees a light flash.
        val saved = runBlocking { settings.themeMode.first() }
        // Paint the window and set the bars for the first frame, before Compose draws anything.
        val systemIsDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val startDark = resolveDarkTheme(saved, systemIsDark)
        window.setBackgroundDrawable(
            ColorDrawable((if (startDark) DarkPalette else LightPalette).background.toArgb())
        )
        applyBarStyle(startDark)
        setContent {
            val mode by settings.themeMode.collectAsState(initial = saved)
            val dark = resolveDarkTheme(mode, isSystemInDarkTheme())
            DisposableEffect(dark) {
                applyBarStyle(dark)
                onDispose { }
            }
            ChapterPlayerTheme(mode) {
                AppNavigation()
            }
        }
    }

    // singleTop: a tap on the notification while the app is open arrives here, not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenPlayer(intent)
    }

    private fun handleOpenPlayer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            // Consumed: a rotation re-creates the activity with the same intent and must not open the player again.
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            openPlayerRequest.request()
        }
    }

    /** Android 13 and later: ask for notifications on the first launch only. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("startup", MODE_PRIVATE)
        if (granted || prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun applyBarStyle(dark: Boolean) {
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
}
