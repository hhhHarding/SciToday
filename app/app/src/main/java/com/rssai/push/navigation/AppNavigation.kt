package com.rssai.push.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rssai.push.data.ApiClient
import com.rssai.push.ui.LogsScreen
import com.rssai.push.ui.MessagesScreen
import com.rssai.push.ui.ReadingDetailScreen
import com.rssai.push.ui.ReadingScreen
import com.rssai.push.ui.SettingsScreen
import com.rssai.push.ui.WebViewScreen
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Messages : Screen("messages", "消息", Icons.Default.Email)
    data object Reading : Screen("reading", "阅读", Icons.AutoMirrored.Filled.List)
    data object Settings : Screen("settings", "设置", Icons.Default.Settings)
}

// 推送通知的 deep link 目标：Reading=PDF 详情页，Digest=RSS 摘要 WebView。
sealed class DeepLink {
    data class Reading(val filename: String) : DeepLink()
    data class Digest(val filename: String) : DeepLink()
}

@Composable
fun AppNavigation(deepLink: DeepLink? = null) {
    val navController = rememberNavController()
    val screens = listOf(Screen.Messages, Screen.Reading, Screen.Settings)

    LaunchedEffect(deepLink) {
        when (deepLink) {
            is DeepLink.Reading -> {
                val encodedFile = URLEncoder.encode(deepLink.filename, "UTF-8")
                val encodedTitle = URLEncoder.encode("PDF 总结", "UTF-8")
                navController.navigate("reading_detail/$encodedFile/$encodedTitle")
            }
            is DeepLink.Digest -> {
                val encodedUrl = URLEncoder.encode(ApiClient.inboxUrl(deepLink.filename), "UTF-8")
                val encodedTitle = URLEncoder.encode("论文总结", "UTF-8")
                navController.navigate("webview/$encodedUrl/$encodedTitle")
            }
            null -> {}
        }
    }

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            if (currentRoute in screens.map { it.route }) {
                NavigationBar {
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Messages.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 } },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 12 } },
        ) {
            composable(Screen.Messages.route) {
                MessagesScreen(
                    onOpenDigest = { filename, title ->
                        val encodedUrl = URLEncoder.encode(ApiClient.inboxUrl(filename), "UTF-8")
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        navController.navigate("webview/$encodedUrl/$encodedTitle")
                    }
                )
            }
            composable(Screen.Reading.route) {
                ReadingScreen(
                    onOpenDetail = { filename, title ->
                        val encodedFile = URLEncoder.encode(filename, "UTF-8")
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        navController.navigate("reading_detail/$encodedFile/$encodedTitle")
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpenLogs = { navController.navigate("logs") }
                )
            }
            composable("logs") { LogsScreen() }
            composable(
                "webview/{url}/{title}",
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val url = URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8")
                val title = URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
                WebViewScreen(url = url, title = title) {
                    navController.popBackStack()
                }
            }
            composable(
                "reading_detail/{filename}/{title}",
                arguments = listOf(
                    navArgument("filename") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val filename = URLDecoder.decode(backStackEntry.arguments?.getString("filename") ?: "", "UTF-8")
                val title = URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
                ReadingDetailScreen(filename = filename, title = title) {
                    navController.popBackStack()
                }
            }
        }
    }
}
