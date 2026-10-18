package com.chapterplayer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.chapterplayer.ui.theme.TextSecondary

/** The gear in a tab's top bar that opens Settings. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
    }
}
