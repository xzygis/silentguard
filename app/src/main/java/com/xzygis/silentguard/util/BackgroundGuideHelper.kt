package com.xzygis.silentguard.util

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 后台保活引导工具。
 * 检测国产 ROM 厂商，引导用户跳转到对应的"自启动管理"和"电池优化"设置页面。
 */
object BackgroundGuideHelper {

    private const val TAG = "BackgroundGuide"
    private const val PREF_NAME = "background_guide"
    private const val KEY_GUIDED = "has_shown_guide"

    /**
     * 判断是否需要显示后台保活引导（仅首次启动守护时弹出）
     */
    fun shouldShowGuide(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_GUIDED, false) && isRestrictedRom()
    }

    /**
     * 标记已显示过引导
     */
    fun markGuideShown(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GUIDED, true).apply()
    }

    /**
     * 显示后台保活引导对话框
     */
    fun showGuideDialog(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val steps = getStepsForManufacturer(manufacturer)

        AlertDialog.Builder(context)
            .setTitle("开启后台运行权限")
            .setMessage(
                "为确保守护功能在后台持续运行，请完成以下设置：\n\n" +
                steps +
                "\n\n未开启以上设置时，系统可能在清理后台时终止守护服务。"
            )
            .setPositiveButton("去设置") { _, _ ->
                openBackgroundSettings(context)
                markGuideShown(context)
            }
            .setNeutralButton("查看电池设置") { _, _ ->
                openBatterySettings(context)
                markGuideShown(context)
            }
            .setNegativeButton("已知晓") { _, _ ->
                markGuideShown(context)
            }
            .setCancelable(false)
            .show()
    }

    private fun isRestrictedRom(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer in listOf(
            "xiaomi", "redmi", "huawei", "honor",
            "oppo", "vivo", "oneplus", "realme",
            "meizu", "samsung", "zte", "lenovo"
        )
    }

    private fun getStepsForManufacturer(manufacturer: String): String {
        return when {
            manufacturer in listOf("xiaomi", "redmi") -> {
                "① 设置 → 应用管理 → SilentGuard → 自启动（开启）\n" +
                "② 设置 → 电池 → SilentGuard → 无限制\n" +
                "③ 最近任务中长按 SilentGuard 卡片 → 加锁"
            }
            manufacturer in listOf("huawei", "honor") -> {
                "① 设置 → 应用启动管理 → SilentGuard → 手动管理（全部开启）\n" +
                "② 设置 → 电池 → 更多电池设置 → 关闭休眠时关闭应用\n" +
                "③ 最近任务中下滑 SilentGuard 卡片 → 加锁"
            }
            manufacturer in listOf("oppo", "realme", "oneplus") -> {
                "① 设置 → 应用管理 → SilentGuard → 耗电保护 → 允许后台运行\n" +
                "② 设置 → 电池 → 自启动管理 → 允许 SilentGuard\n" +
                "③ 最近任务中下滑 SilentGuard 卡片 → 加锁"
            }
            manufacturer == "vivo" -> {
                "① 设置 → 电池 → 后台高耗电 → 允许 SilentGuard\n" +
                "② i管家 → 应用管理 → 自启动管理 → 允许 SilentGuard\n" +
                "③ 最近任务中下滑 SilentGuard 卡片 → 加锁"
            }
            manufacturer == "samsung" -> {
                "① 设置 → 电池 → 后台使用限制 → 移除 SilentGuard\n" +
                "② 设置 → 应用 → SilentGuard → 电池 → 不受限\n" +
                "③ 最近任务中长按 SilentGuard → 锁定此应用"
            }
            manufacturer == "meizu" -> {
                "① 设置 → 应用管理 → SilentGuard → 权限管理 → 后台管理 → 允许后台运行\n" +
                "② 手机管家 → 权限管理 → 自启动管理 → 允许 SilentGuard"
            }
            else -> {
                "① 设置 → 电池/电池优化 → 找到 SilentGuard → 设为「不优化」\n" +
                "② 设置 → 应用 → SilentGuard → 确认允许自启动和后台运行"
            }
        }
    }

    /**
     * 尝试跳转到自启动管理页面
     */
    private fun openBackgroundSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = getAutoStartIntents(manufacturer, context)

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.d(TAG, "跳转失败: ${intent.component}, 尝试下一个")
            }
        }

        // 所有特定 Intent 都失败，打开通用应用详情页
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法打开任何设置页面: ${e.message}")
        }
    }

    private fun openBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "无法打开电池设置: ${e2.message}")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun getAutoStartIntents(manufacturer: String, context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()

        when {
            manufacturer in listOf("xiaomi", "redmi") -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )))
            }
            manufacturer in listOf("huawei", "honor") -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )))
            }
            manufacturer in listOf("oppo", "realme", "oneplus") -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )))
            }
            manufacturer == "vivo" -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )))
                intents.add(Intent().setComponent(ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )))
            }
            manufacturer == "samsung" -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )))
            }
            manufacturer == "meizu" -> {
                intents.add(Intent().setComponent(ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"
                )))
            }
        }

        return intents
    }
}
