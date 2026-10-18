package com.qingsongreader.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.ui.library.LibraryScreen
import com.qingsongreader.ui.reader.ReaderScreen
import com.qingsongreader.ui.settings.SettingsScreen
import com.qingsongreader.ui.stats.StatsScreen

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val READER = "reader/{bookId}"
    fun reader(bookId: String) = "reader/$bookId"
}

@Composable
fun AppNav(
    navController: NavHostController,
    settingsRepository: SettingsRepository,
    incomingUri: Uri?
) {
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                settingsRepository = settingsRepository,
                incomingUri = incomingUri,
                onOpenBook = { navController.navigate(Routes.reader(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenStats = { navController.navigate(Routes.STATS) }
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            ReaderScreen(
                bookId = bookId,
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
