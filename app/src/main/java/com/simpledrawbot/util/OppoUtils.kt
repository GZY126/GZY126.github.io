package com.simpledrawbot.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 针对 OPPO / ColorOS 的深度适配工具
 */
object OppoUtils {

    /**
     * 是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化（标准 Android API）
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ContextCompat.startActivity(context, intent, null)
        }
    }

    /**
     * 尝试打开 ColorOS 应用自启动/后台管理页面
     */
    fun openColorOsAppManager(context: Context) {
        val packageName = context.packageName
        val intents = listOf(
            // ColorOS 手机管家 -> 权限隐私 -> 自启动管理
            Intent().apply {
                setClassName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
            },
            Intent().apply {
                setClassName("com.coloros.phonemanager", "com.coloros.phonemanager.FakeActivity")
            },
            Intent().apply {
                setClassName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            },
            Intent().apply {
                setClassName("com.coloros.oppoguardelf", "com.coloros.oppoguardelf.MainActivity")
            },
            // 通用应用详情页兜底
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                try {
                    ContextCompat.startActivity(context, intent, null)
                    return
                } catch (_: Exception) { }
            }
        }
    }
}
