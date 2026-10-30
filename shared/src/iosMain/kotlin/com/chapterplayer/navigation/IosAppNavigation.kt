package com.chapterplayer.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import com.chapterplayer.data.AppInfo
import com.chapterplayer.ui.settings.ServerSetupScreen
import com.chapterplayer.ui.settings.ServersSettingsScreen
import com.chapterplayer.ui.settings.SettingsPage
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.koinInject

/** The route of the iOS start page (the Android routes are used for the rest). */
internal const val IOS_HOME_ROUTE = "ios_home"

/**
 * The iOS app's navigation in Stage 5 step 1: only what works on iOS so far. A start page says so and opens
 * Settings, Servers, where servers are added (Audiobookshelf or Plex), edited, deleted and their sync status shown;
 * the library, the player and downloads come in later steps.
 */
@Composable
fun IosAppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = IOS_HOME_ROUTE) {
        composable(IOS_HOME_ROUTE) {
            IosHomeScreen(onOpenServers = { navController.navigate(Screen.SettingsServers.route) { launchSingleTop = true } })
        }
        composable(Screen.SettingsServers.route) {
            ServersSettingsScreen(
                onAddServer = { navController.navigate(Screen.ServerSetup.createRoute()) },
                onEditServer = { serverId -> navController.navigate(Screen.ServerSetup.createRoute(serverId)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ServerSetup.route) { backStackEntry ->
            val serverId = backStackEntry.arguments?.read { getStringOrNull(Screen.ServerSetup.ARG_SERVER_ID) }
            ServerSetupScreen(
                serverId = serverId?.takeIf { it != Screen.ServerSetup.NEW_SERVER },
                onDone = { navController.popBackStack() }
            )
        }
    }
}

/** The start page: the app's name and version, what this build can do, and the way to the servers. */
@Composable
private fun IosHomeScreen(onOpenServers: () -> Unit, appInfo: AppInfo = koinInject()) {
    // The start page has nowhere to go back to, so it has no back arrow.
    SettingsPage(title = "ChapterPlayer", onBack = null) {
        Text("Version ${appInfo.versionName}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        Text(
            "This is an early iPhone build: you can add your Audiobookshelf and Plex servers and see their status. " +
                "The library, playback and downloads are not available on iOS yet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenServers,
            colors = ButtonDefaults.buttonColors(containerColor = AmberGold, contentColor = MaterialTheme.colorScheme.onPrimary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Servers")
        }
    }
}
