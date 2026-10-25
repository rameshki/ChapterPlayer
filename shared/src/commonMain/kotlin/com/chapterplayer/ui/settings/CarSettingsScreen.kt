package com.chapterplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/** Settings, Android Auto. */
@Composable
fun CarSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val resumeOnCarConnect by viewModel.resumeOnCarConnect.collectAsStateWithLifecycle()
    SettingsPage(title = "Android Auto", onBack = onBack) {
        SectionTitle("Android Auto")
        SwitchCard(
            title = "Resume when connected to the car",
            description = "Plays your last book when the phone connects to Android Auto and nothing is playing.",
            checked = resumeOnCarConnect,
            onChange = viewModel::setResumeOnCarConnect
        )
    }
}
