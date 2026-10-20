package com.chapterplayer.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import org.koin.compose.koinInject
import com.chapterplayer.data.sync.LibrarySyncCoordinator
import com.chapterplayer.ui.details.BookDetailsScreen
import com.chapterplayer.ui.downloads.DownloadsScreen
import com.chapterplayer.ui.home.HomeScreen
import com.chapterplayer.ui.library.GroupDetailScreen
import com.chapterplayer.ui.library.LibraryScreen
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.library.decodeGroupKey
import com.chapterplayer.ui.player.PlayerScreen
import com.chapterplayer.ui.search.SearchScreen
import com.chapterplayer.ui.settings.SettingsScreen
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.NavBarSurface
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

data class BottomNavItem(
    val label: String,
    val screen: Screen,
    val icon: ImageVector
)

/** The tabs, in bar order. Player and Settings are destinations, not tabs. */
val bottomNavItems = listOf(
    BottomNavItem("Home", Screen.Home, Icons.Default.Home),
    BottomNavItem("Library", Screen.Library, Icons.Default.LibraryBooks),
    BottomNavItem("Downloads", Screen.Downloads, Icons.Default.Download),
    BottomNavItem("Search", Screen.Search, Icons.Default.Search)
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Starts the app-wide library sync once, so a new install fills in whichever tab is open.
    val syncCoordinator = koinInject<LibrarySyncCoordinator>()
    LaunchedEffect(syncCoordinator) { syncCoordinator.start() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // The bar belongs to the four tabs; Settings, server setup and the player cover the screen.
    val showBottomBar = currentDestination == null ||
        bottomNavItems.any { item -> currentDestination.hierarchy.any { it.route == item.screen.route } }

    val openSettings = {
        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
    }
    // A book tap opens its details; the player is reached from there once playback exists.
    val openBook = { bookId: String ->
        val entry = navController.currentBackStackEntry
        val shownId = entry?.arguments?.read { getStringOrNull(Screen.BookDetails.ARG_ID) }
        // Single-top would replace a different book on top; push, except when this book is already showing.
        if (!Screen.BookDetails.isShowing(entry?.destination?.route, shownId, bookId)) {
            navController.navigate(Screen.BookDetails.createRoute(bookId))
        }
    }
    val openGroup = { kind: LibraryView, key: String ->
        navController.navigate(Screen.GroupDetail.createRoute(kind, key))
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = NavBarSurface) {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = { navController.openTab(item.screen) },
                            icon = { Icon(item.icon, contentDescription = null) },
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
                startDestination = Screen.Home.route
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(onBookClick = openBook, onOpenSettings = { openSettings() })
                }

                composable(Screen.Library.route) {
                    LibraryScreen(
                        onBookClick = openBook,
                        onGroupClick = openGroup,
                        onOpenSettings = { openSettings() }
                    )
                }

                composable(Screen.GroupDetail.route) { backStackEntry ->
                    val args = backStackEntry.arguments
                    val kind = args?.read { getStringOrNull(Screen.GroupDetail.ARG_KIND) }
                        ?.let { name -> LibraryView.entries.firstOrNull { it.name == name && it.isGrouped } }
                    val key = args?.read { getStringOrNull(Screen.GroupDetail.ARG_KEY) }?.let(::decodeGroupKey)
                    if (kind != null && key != null) {
                        GroupDetailScreen(
                            kind = kind,
                            groupKey = key,
                            onBookClick = openBook,
                            onBack = { navController.popBackStack() }
                        )
                    } else {
                        // A damaged link: leave rather than show an empty page.
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    }
                }

                composable(Screen.BookDetails.route) { backStackEntry ->
                    val id = backStackEntry.arguments?.read { getStringOrNull(Screen.BookDetails.ARG_ID) }
                        ?.let(::decodeGroupKey)
                    if (id != null) {
                        BookDetailsScreen(
                            bookId = id,
                            onBack = { navController.popBackStack() },
                            onOpenBook = openBook,
                            onOpenGroup = openGroup
                        )
                    } else {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    }
                }

                composable(Screen.Downloads.route) {
                    DownloadsScreen(onOpenSettings = { openSettings() })
                }

                composable(Screen.Search.route) {
                    SearchScreen(onBookClick = openBook, onOpenSettings = { openSettings() })
                }

                composable(Screen.Player.route) {
                    PlayerScreen(onBack = { navController.popBackStack() })
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onAddServer = {
                            navController.navigate(Screen.ServerSetup.createRoute())
                        },
                        onEditServer = { serverId ->
                            navController.navigate(Screen.ServerSetup.createRoute(serverId))
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Screen.ServerSetup.route) { backStackEntry ->
                    val serverId = backStackEntry.arguments?.read { getStringOrNull(Screen.ServerSetup.ARG_SERVER_ID) }
                    com.chapterplayer.ui.settings.ServerSetupScreen(
                        serverId = serverId?.takeIf { it != Screen.ServerSetup.NEW_SERVER },
                        onDone = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/** Switch tab without stacking: back to the start, keep each tab's state, reuse the top entry. */
private fun NavController.openTab(screen: Screen) {
    navigate(screen.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
