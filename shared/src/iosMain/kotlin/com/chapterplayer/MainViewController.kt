package com.chapterplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.chapterplayer.ui.theme.ChapterPlayerTheme

/**
 * The root view controller for the iOS app. It only shows the themed app name for now:
 * the database, networking and navigation get their iOS parts in Stage 5.
 */
fun MainViewController() = ComposeUIViewController {
    ChapterPlayerTheme {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("ChapterPlayer", style = MaterialTheme.typography.headlineLarge)
        }
    }
}
