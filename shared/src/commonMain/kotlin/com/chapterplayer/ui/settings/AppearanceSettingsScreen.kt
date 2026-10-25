package com.chapterplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Appearance: the theme. */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    SettingsPage(title = "Appearance", onBack = onBack) {
        SectionTitle("Appearance")
        AppearanceCard(selected = themeMode, onSelect = viewModel::setThemeMode)
    }
}
