package com.xzygis.silentguard

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
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
import com.xzygis.silentguard.service.MonitorForegroundService
import com.xzygis.silentguard.util.BackgroundGuideHelper
import com.xzygis.silentguard.service.SmsNotificationListenerService
import com.xzygis.silentguard.ui.navigation.Screen
import com.xzygis.silentguard.ui.screen.ActivityLogScreen
import com.xzygis.silentguard.ui.screen.DashboardScreen
import com.xzygis.silentguard.ui.screen.MapScreen
import com.xzygis.silentguard.ui.screen.SettingsScreen
import com.xzygis.silentguard.ui.theme.SilentGuardTheme
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.xzygis.silentguard.diagnostics.AppDiagnostics
import com.xzygis.silentguard.diagnostics.GuardHealth
import com.xzygis.silentguard.mail.EmailScheduleWorker
import com.xzygis.silentguard.mail.MailWorker

class MainActivity : ComponentActivity() {

    private lateinit var appConfig: AppConfig

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            // 检查短信权限是否被拒绝
            val smsDenied = denied.any {
                it == Manifest.permission.RECEIVE_SMS || it == Manifest.permission.READ_SMS
            }
            if (smsDenied && !isSmsNotificationReadEnabled()) {
                showSmsNotificationReadGuide()
            } else if (denied.isNotEmpty()) {
                Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_LONG).show()
            }
        }
        restoreGuardingIfNeeded()
        if (AppDiagnostics.hasLocationPermission(this) && !AppDiagnostics.hasBackgroundLocationPermission(this)) {
            AlertDialog.Builder(this).setTitle("后台定位需要单独授权")
                .setMessage("前台定位已授权。若希望锁屏后持续守护和后台恢复，请在位置权限中选择「始终允许」。")
                .setPositiveButton("去设置") { _, _ -> startActivity(AppDiagnostics.appDetailsIntent(this)) }
                .setNegativeButton("稍后", null).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appConfig = AppConfig(this)

        restoreGuardingIfNeeded()
        com.xzygis.silentguard.data.RetentionWorker.schedule(this)
        lifecycleScope.launch { MailWorker.recover(this@MainActivity) }

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
        val database = remember { AppDatabase.getInstance(this@MainActivity) }
        val dao = remember(database) { database.monitorEventDao() }
        val mailRecordDao = remember(database) { database.mailSendRecordDao() }

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
                                indicatorColor = MaterialTheme.colorScheme.surface
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
                        isGuarding = config.isGuardingEnabled,
                        dao = dao,
                        mailRecordDao = mailRecordDao,
                        config = config,
                        onToggleGuarding = { toggleGuarding(it) },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToActivityLog = {
                            navController.navigate(Screen.ActivityLog.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToMap = {
                            navController.navigate(Screen.Map.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(Screen.ActivityLog.route) {
                    ActivityLogScreen(
                        dao = dao,
                        mailRecordDao = mailRecordDao
                    )
                }
                composable(Screen.Map.route) {
                    MapScreen(dao = dao)
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        appConfig = appConfig,
                        isGuarding = config.isGuardingEnabled,
                        onToggleGuarding = { toggleGuarding(it) }
                    )
                }
            }
        }
    }

    /**
     * App 启动时检查守护状态，如果之前已开启则自动恢复服务
     */
    private fun restoreGuardingIfNeeded() {
        lifecycleScope.launch {
            val config = appConfig.getConfig()
            if (config.isGuardingEnabled) {
                GuardHealth.start(this@MainActivity, fromVisibleActivity = true)
            }
        }
    }

    private fun toggleGuarding(enabled: Boolean) {
        lifecycleScope.launch {
            val current = appConfig.getConfig()
            if (enabled && (current.senderEmail.isBlank() || current.senderPassword.isBlank() ||
                        current.recipientEmail.isBlank() ||
                        com.xzygis.silentguard.config.ConfigValidation.errors(current).isNotEmpty())) {
                Toast.makeText(this@MainActivity, "请先保存完整有效的邮件配置", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (enabled && !AppDiagnostics.hasLocationPermission(this@MainActivity)) {
                requestPermissions()
                Toast.makeText(this@MainActivity, "授权后请再次开启守护", Toast.LENGTH_LONG).show()
                return@launch
            }
            appConfig.setGuardingEnabled(enabled)
        val serviceIntent = Intent(this@MainActivity, MonitorForegroundService::class.java)
        if (enabled) {
            if (!GuardHealth.start(this@MainActivity, fromVisibleActivity = true)) {
                Toast.makeText(this@MainActivity, "服务启动受限，请检查权限后重试", Toast.LENGTH_LONG).show()
                return@launch
            }
            MailWorker.recover(this@MainActivity)
            Toast.makeText(this@MainActivity, "守护已启动", Toast.LENGTH_SHORT).show()

            // 首次启动守护时，在国产 ROM 上引导用户开启后台运行权限
            if (BackgroundGuideHelper.shouldShowGuide(this@MainActivity)) {
                BackgroundGuideHelper.showGuideDialog(this@MainActivity)
            }
        } else {
            stopService(serviceIntent)
            EmailScheduleWorker.cancel(this@MainActivity)
            Toast.makeText(this@MainActivity, "守护已停止，待发送任务已暂停", Toast.LENGTH_SHORT).show()
        }
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
        } else {
            // 权限已全部授予，但也检查一下短信通知读取兼容方案是否需要启用
            checkSmsNotificationReadFallback()
        }
    }

    /**
     * 检查短信权限状态，如果被拒则引导开启短信通知读取权限
     */
    private fun checkSmsNotificationReadFallback() {
        val smsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!smsGranted && !isSmsNotificationReadEnabled()) {
            showSmsNotificationReadGuide()
        }
    }

    /**
     * 检查短信通知读取权限是否已开启
     */
    private fun isSmsNotificationReadEnabled(): Boolean {
        val cn = ComponentName(this, SmsNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    /**
     * 弹出对话框引导用户开启短信通知读取权限
     */
    private fun showSmsNotificationReadGuide() {
        AlertDialog.Builder(this)
            .setTitle("短信记录权限")
            .setMessage(
                "系统拒绝了短信权限的授予。\n\n" +
                "您可以开启「通知使用权」作为替代方案，" +
                "应用将读取短信通知内容，用于生成邮件提醒。\n\n" +
                "点击「去设置」后，在列表中找到 SilentGuard 并开启。"
            )
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }
}
