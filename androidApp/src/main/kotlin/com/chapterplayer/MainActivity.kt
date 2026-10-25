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
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.chapterplayer.data.permissions.AndroidNotificationPermission
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.widgets.EXTRA_RESUME_BOOK_ID
import com.chapterplayer.data.widgets.WidgetResumer
import com.chapterplayer.domain.playback.EXTRA_OPEN_PLAYER
import com.chapterplayer.domain.playback.OpenPlayerRequest
import com.chapterplayer.navigation.AppNavigation
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import com.chapterplayer.ui.theme.DarkPalette
import com.chapterplayer.ui.theme.LightPalette
import com.chapterplayer.ui.theme.resolveDarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Single-activity entry point for the app.
 * Navigation between screens is handled entirely by Compose Navigation.
 */
class MainActivity : ComponentActivity() {

    private val settings: AppSettings by inject()
    private val openPlayerRequest: OpenPlayerRequest by inject()
    private val widgetResumer: WidgetResumer by inject()

    private val downloadNotifications: AndroidNotificationPermission by inject()

    // Playback and downloads work without notifications; the answer only matters to a download waiting for it.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            downloadNotifications.onResult(granted)
        }

    private val askForNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadNotifications.attach(askForNotifications)
        askForNotificationsOnce()
        handleOpenPlayer(intent)
        handleWidgetResume(intent)
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

    override fun onDestroy() {
        downloadNotifications.detach(askForNotifications)
        super.onDestroy()
    }

    // singleTop: a tap on the notification while the app is open arrives here, not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenPlayer(intent)
        handleWidgetResume(intent)
    }

    private fun handleOpenPlayer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            // Consumed: a rotation re-creates the activity with the same intent and must not open the player again.
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            openPlayerRequest.request()
        }
    }

    /** The Continue Listening widget's Resume: start its book, then the player opens (see [WidgetResumer]). */
    private fun handleWidgetResume(intent: Intent?) {
        val bookId = intent?.getStringExtra(EXTRA_RESUME_BOOK_ID) ?: return
        // Consumed, like open_player: a rotation must not start the book again.
        intent.removeExtra(EXTRA_RESUME_BOOK_ID)
        lifecycleScope.launch {
            widgetResumer.resume(bookId).onFailure { failure ->
                failure.message?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
            }
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
