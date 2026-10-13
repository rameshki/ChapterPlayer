package com.chapterplayer.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chapterplayer.ui.library.LibraryScreen
import com.chapterplayer.ui.settings.SettingsScreen
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.NavBarSurface
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

data class BottomNavItem(
    val label: String,
    val screen: Screen,
    val icon: @Composable () -> Unit
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    val bottomNavItems = listOf(
        BottomNavItem("Library", Screen.Library) {
            Icon(Icons.Default.LibraryBooks, contentDescription = "Library")
        },
        BottomNavItem("Player", Screen.Player) {
            Icon(Icons.Default.PlayCircle, contentDescription = "Player")
        },
        BottomNavItem("Search", Screen.Library) {   // Placeholder until Search screen is built
            Icon(Icons.Default.Search, contentDescription = "Search")
        },
        BottomNavItem("Settings", Screen.Settings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = NavBarSurface
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { item ->
                    val isSelected = currentDestination?.hierarchy?.any {
                        it.route == item.screen.route
                    } == true

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            navController.navigate(item.screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = item.icon,
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AmberGold,
                            selectedTextColor = AmberGold,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = SurfaceElevated
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDeep)
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Library.route
            ) {
                composable(Screen.Library.route) {
                    LibraryScreen(
                        onBookClick = { bookId ->
                            navController.navigate(Screen.Player.createRoute(bookId))
                        }
                    )
                }

                composable(Screen.Player.route) {
                    // Player screen stub — built in the next phase
                    Box(Modifier.fillMaxSize().background(BackgroundDeep))
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onAddServer = {
                            navController.navigate(Screen.ServerSetup.createRoute())
                        },
                        onEditServer = { serverId ->
                            navController.navigate(Screen.ServerSetup.createRoute(serverId))
                        }
                    )
                }

                composable(Screen.ServerSetup.route) { backStackEntry ->
                    val serverId = backStackEntry.arguments?.getString(Screen.ServerSetup.ARG_SERVER_ID)
                    com.chapterplayer.ui.settings.ServerSetupScreen(
                        serverId = serverId?.takeIf { it != Screen.ServerSetup.NEW_SERVER },
                        onDone = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
