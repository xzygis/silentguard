package com.xzygis.silentguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.service.MonitorForegroundService
import com.xzygis.silentguard.ui.navigation.Screen
import com.xzygis.silentguard.ui.screen.ActivityLogScreen
import com.xzygis.silentguard.ui.screen.DashboardScreen
import com.xzygis.silentguard.ui.screen.MapScreen
import com.xzygis.silentguard.ui.screen.SettingsScreen
import com.xzygis.silentguard.ui.theme.SilentGuardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appConfig: AppConfig
    private lateinit var mailSender: MailSender

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appConfig = AppConfig(this)
        mailSender = MailSender(this)

        requestPermissions()

        setContent {
            SilentGuardTheme {
                SilentGuardApp()
            }
        }
    }

    @Composable
    private fun SilentGuardApp() {
        val navController = rememberNavController()
        val config by appConfig.configFlow.collectAsState(initial = MonitorConfig())
        val scope = rememberCoroutineScope()
        val dao = remember { AppDatabase.getInstance(this@MainActivity).monitorEventDao() }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    Screen.items.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.label
                                )
                            },
                            label = {
                                Text(
                                    text = screen.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(200)) }
            ) {
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        isMonitoring = config.isMonitoringEnabled,
                        dao = dao,
                        onToggleMonitoring = { toggleMonitoring(it) }
                    )
                }
                composable(Screen.ActivityLog.route) {
                    ActivityLogScreen(dao = dao)
                }
                composable(Screen.Map.route) {
                    MapScreen(dao = dao)
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        appConfig = appConfig,
                        mailSender = mailSender,
                        isMonitoring = config.isMonitoringEnabled,
                        onToggleMonitoring = { toggleMonitoring(it) }
                    )
                }
            }
        }
    }

    private fun toggleMonitoring(enabled: Boolean) {
        kotlinx.coroutines.MainScope().launch {
            appConfig.setMonitoringEnabled(enabled)
        }

        val serviceIntent = Intent(this, MonitorForegroundService::class.java)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "监控已启动", Toast.LENGTH_SHORT).show()
        } else {
            stopService(serviceIntent)
            Toast.makeText(this, "监控已停止", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
