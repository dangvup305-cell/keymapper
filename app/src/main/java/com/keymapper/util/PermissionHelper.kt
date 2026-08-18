package com.keymapper.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.keymapper.accessibility.KeyMapperAccessibilityService

/**
 * 权限检查与跳转（P0-8）。
 *
 * 三项必需/相关权限：
 * 1. 无障碍服务（核心，键盘监听 + 手势注入）
 * 2. `SYSTEM_ALERT_WINDOW` 悬浮窗（跨应用显示）
 * 3. `POST_NOTIFICATIONS` 通知（Android 13+ 前台保活通知需要）
 */
object PermissionHelper {

    /**
     * 无障碍服务是否已开启。
     *
     * 通过 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 判断，
     * 比 `AccessibilityManager.getEnabledAccessibilityServiceList` 更可靠（后者在部分 ROM 上有延迟）。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context.packageName,
            KeyMapperAccessibilityService::class.java.name,
        )
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (TextUtils.isEmpty(enabledServices)) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next()) ?: continue
            if (component == expected) return true
            // 兼容部分 ROM 只写短类名的情况
            if (component.packageName == expected.packageName &&
                component.className.endsWith(KeyMapperAccessibilityService::class.java.simpleName)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 无障碍服务是否真正连接（进程内单例已就绪）。
     * 与 [isAccessibilityEnabled] 配合使用：开关开着但服务未连接时提示用户重启开关。
     */
    fun isAccessibilityServiceRunning(): Boolean = KeyMapperAccessibilityService.isRunning()

    /**
     * 悬浮窗权限（`SYSTEM_ALERT_WINDOW`）是否已授予。
     * API 23 以下默认授予；minSdk 24 下始终走 `canDrawOverlays`。
     */
    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    /**
     * 通知权限是否已授予（Android 13+ 需要运行时授权，之前版本默认有）。
     */
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 全部必需权限（无障碍 + 悬浮窗）是否就绪。通知权限不阻塞主流程。
     */
    fun allRequiredGranted(context: Context): Boolean =
        isAccessibilityEnabled(context) && canDrawOverlays(context)

    /** 缺失的必需权限清单，供 UI 提示。 */
    fun missingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!isAccessibilityEnabled(context)) missing.add("无障碍服务")
        if (!canDrawOverlays(context)) missing.add("悬浮窗权限")
        if (!hasNotificationPermission(context)) missing.add("通知权限")
        return missing
    }

    /**
     * 跳转到系统无障碍设置页。失败时回退到系统设置首页。
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startSafely(context, intent, Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * 跳转到本应用的悬浮窗授权页。
     */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startSafely(context, intent, appDetailsIntent(context))
    }

    /**
     * 跳转到通知设置页（Android 8+ 走应用通知设置，更低版本走应用详情）。
     */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            appDetailsIntent(context)
        }
        startSafely(context, intent, appDetailsIntent(context))
    }

    /** 本应用的「应用详情」页 Intent。 */
    fun appDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * 先尝试主 Intent，失败后回退备用 Intent，两者都失败则静默忽略（不让引导页崩溃）。
     */
    private fun startSafely(context: Context, primary: Intent, fallback: Intent) {
        try {
            context.startActivity(primary)
        } catch (e: Exception) {
            try {
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (ignored: Exception) {
                // 极端 ROM 上两个设置页都不可达，交由 UI 的文案提示用户手动开启
            }
        }
    }
}
