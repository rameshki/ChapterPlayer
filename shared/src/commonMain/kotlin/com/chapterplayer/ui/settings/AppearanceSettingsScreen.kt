package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Appearance: the theme and the font. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appFont by viewModel.appFont.collectAsStateWithLifecycle()
    SettingsPage(title = "Appearance", onBack = onBack) {
        SectionTitle("Appearance")
        AppearanceCard(selected = themeMode, onSelect = viewModel::setThemeMode)
        Spacer(Modifier.height(16.dp))
        SectionTitle("Font")
        FontCard(selected = appFont, onSelect = viewModel::setAppFont)
    }
}
