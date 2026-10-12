package com.chapterplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.chapterplayer.navigation.AppNavigation
import com.chapterplayer.ui.theme.ChapterPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity entry point for the app.
 * Navigation between screens is handled entirely by Compose Navigation.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChapterPlayerTheme {
                AppNavigation()
            }
        }
    }
}
