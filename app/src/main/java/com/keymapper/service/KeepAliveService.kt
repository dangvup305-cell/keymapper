package com.keymapper.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.keymapper.MainActivity
import com.keymapper.R
import com.keymapper.data.ConfigRepository
import com.keymapper.ui.clicker.AutoClickerActivity
import com.keymapper.util.Constants
import org.koin.android.ext.android.inject
import org.xmlpull.v1.XmlPullParser

/**
 * 前台保活服务（P0-6）。
 *
 * 保活闭环（Q1 决议）：
 * 1. `startForeground` 展示常驻通知，显著降低被 LMK 回收的概率（Android 8+ 强制要求）；
 * 2. [onDestroy] 中通过 `AlarmManager` 做一次尽力而为的自重启；
 * 3. 无障碍服务被系统回收后，只要开关仍打开，系统会自行重启它，并在
 *    [com.keymapper.accessibility.KeyMapperAccessibilityService.onUnbind] 中回调 [requestRestart]。
 *
 * 保活参数（渠道名、重启延时）从 `res/xml/keep_alive_service_config.xml` 读取，改策略无需改代码。
 */
class KeepAliveService : Service() {

    private val repository: ConfigRepository by inject()

    /** 从 XML 解析出的保活参数。 */
    private var config: KeepAliveConfig = KeepAliveConfig()

    /** 配置变化时刷新通知文案。 */
    private val configChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_CONFIG_CHANGED,
                Constants.ACTION_BINDINGS_UPDATED,
                Constants.ACTION_SWITCH_CONFIG,
                -> updateNotification()
            }
        }
    }

    private var receiverRegistered = false

    /**
     * P2-3 低电量监听：电量跌破 [Constants.LOW_BATTERY_THRESHOLD_PERCENT] 时广播
     * [Constants.ACTION_LOW_BATTERY]，由悬浮窗服务降低刷新率。
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> publishLowBattery(true)
                Intent.ACTION_BATTERY_OKAY -> publishLowBattery(false)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level < 0 || scale <= 0) return
                    val percent = level * 100 / scale
                    publishLowBattery(percent <= Constants.LOW_BATTERY_THRESHOLD_PERCENT)
                }
            }
        }
    }

    private var batteryReceiverRegistered = false

    /** 最近一次已广播的低电量状态，避免 BATTERY_CHANGED 高频重复广播。 */
    private var lastLowBatteryState: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        config = parseConfig()
        createNotificationChannel()
        startForegroundCompat()
        registerConfigReceiver()
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 无论何种唤起路径（首次启动 / 系统重启 / AlarmManager 自重启）都要确保通知在前台
        startForegroundCompat()
        if (intent?.action == Constants.ACTION_RESTART_SERVICE) {
            Log.i(TAG, "收到自重启信号，保活服务已就绪")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterConfigReceiver()
        unregisterBatteryReceiver()
        if (config.restartEnabled) {
            scheduleSelfRestart()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉应用时不结束保活（Manifest 已声明 stopWithTask=false，这里再兜一层）
        if (config.restartEnabled) {
            scheduleSelfRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            config.channelId,
            config.channelName,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val activeName = repository.activeConfigName()
        val text = if (activeName.isNullOrBlank()) {
            getString(R.string.notification_text_idle)
        } else {
            getString(R.string.notification_text_running, activeName)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            pendingIntentFlags(),
        )

        // 通知栏快捷按钮：一键打开连点器（模式二·屏幕辅助）
        val clickerIntent = PendingIntent.getActivity(
            this,
            REQUEST_CLICKER,
            Intent(this, AutoClickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, config.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_clicker), clickerIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 按 SDK 版本差异调用 startForeground（Android 10+ 需要声明服务类型）。 */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Constants.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限，失败时退化为普通通知，不影响无障碍监听
            Log.w(TAG, "startForeground 失败：${e.message}")
            getSystemService(NotificationManager::class.java)
                ?.notify(Constants.NOTIFICATION_ID, notification)
        }
    }

    /** 刷新通知内容（配置切换后调用）。 */
    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(Constants.NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "更新通知失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 广播注册
    // ------------------------------------------------------------------

    private fun registerConfigReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_CONFIG_CHANGED)
            addAction(Constants.ACTION_BINDINGS_UPDATED)
            addAction(Constants.ACTION_SWITCH_CONFIG)
        }
        registerReceiverCompat(configChangedReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterConfigReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(configChangedReceiver)
        } catch (ignored: Exception) {
            // 未注册成功时忽略
        }
        receiverRegistered = false
    }

    // ------------------------------------------------------------------
    // P2-3 低电量降频
    // ------------------------------------------------------------------

    /**
     * 注册系统电量广播。
     *
     * 电量广播是系统广播，必须用 `RECEIVER_EXPORTED`（Android 13+），
     * 与应用内部广播（`RECEIVER_NOT_EXPORTED`）区分开。
     */
    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(batteryReceiver, filter)
            }
            batteryReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "注册电量广播失败：${e.message}")
        }
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
        } catch (ignored: Exception) {
            // 未注册成功时忽略
        }
        batteryReceiverRegistered = false
    }

    /**
     * 广播低电量状态变化。
     *
     * - 用户在设置里关闭了「低电量降频」时，一律按「非低电量」广播，保证开关立即生效
     * - 状态未变化时不重复广播（`ACTION_BATTERY_CHANGED` 频率很高）
     */
    private fun publishLowBattery(low: Boolean) {
        val effective = low && repository.isLowBatterySaverEnabled()
        if (lastLowBatteryState == effective) return
        lastLowBatteryState = effective
        try {
            sendBroadcast(
                Intent(Constants.ACTION_LOW_BATTERY)
                    .putExtra(Constants.EXTRA_KEYMAPPER_ENABLED, effective)
                    .setPackage(packageName),
            )
            Log.i(TAG, "低电量降频状态广播：$effective")
        } catch (e: Exception) {
            Log.w(TAG, "广播低电量状态失败：${e.message}")
        }
    }

    /** Android 13+ 需要显式声明广播是否导出。 */
    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    // ------------------------------------------------------------------
    // 自重启
    // ------------------------------------------------------------------

    /**
     * 尽力而为的自重启：用 AlarmManager 延时唤起自身。
     *
     * 说明：Android 12+ 对「后台启动前台服务」有限制，本机制不保证 100% 成功，
     * 因此真正的保活主路径仍是「无障碍开关保持开启 → 系统自动重启无障碍服务」。
     */
    private fun scheduleSelfRestart() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(applicationContext, KeepAliveService::class.java).apply {
            action = Constants.ACTION_RESTART_SERVICE
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext,
                REQUEST_RESTART,
                intent,
                pendingIntentFlags(),
            )
        } else {
            PendingIntent.getService(
                applicationContext,
                REQUEST_RESTART,
                intent,
                pendingIntentFlags(),
            )
        }
        try {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + config.restartDelayMs,
                pendingIntent,
            )
            Log.i(TAG, "已安排 ${config.restartDelayMs}ms 后自重启")
        } catch (e: Exception) {
            Log.w(TAG, "安排自重启失败：${e.message}")
        }
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    // ------------------------------------------------------------------
    // XML 参数解析
    // ------------------------------------------------------------------

    /**
     * 解析 `res/xml/keep_alive_service_config.xml`。任何异常都退化为代码内默认值。
     */
    private fun parseConfig(): KeepAliveConfig {
        var result = KeepAliveConfig(
            channelId = Constants.NOTIFICATION_CHANNEL_ID,
            channelName = getString(R.string.notification_channel_name),
            restartDelayMs = Constants.SERVICE_RESTART_DELAY_MS,
            restartEnabled = true,
        )
        val parser = try {
            resources.getXml(R.xml.keep_alive_service_config)
        } catch (e: Exception) {
            Log.w(TAG, "保活配置资源不可读，使用默认值：${e.message}")
            return result
        }
        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == TAG_ROOT) {
                    val channelId = parser.getAttributeValue(null, "channelId")
                    val channelName = parser.getAttributeValue(null, "channelName")
                    val delay = parser.getAttributeValue(null, "restartDelayMs")
                    val enabled = parser.getAttributeValue(null, "restartEnabled")
                    result = KeepAliveConfig(
                        channelId = channelId?.takeIf { it.isNotBlank() } ?: result.channelId,
                        channelName = channelName?.takeIf { it.isNotBlank() }
                            ?: result.channelName,
                        restartDelayMs = delay?.toLongOrNull() ?: result.restartDelayMs,
                        restartEnabled = enabled?.toBooleanStrictOrNull()
                            ?: result.restartEnabled,
                    )
                    break
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "保活配置解析失败，使用默认值：${e.message}")
        } finally {
            try {
                parser.close()
            } catch (ignored: Exception) {
                // 关闭失败不影响主流程
            }
        }
        return result
    }

    /**
     * 保活参数。
     */
    data class KeepAliveConfig(
        val channelId: String = Constants.NOTIFICATION_CHANNEL_ID,
        val channelName: String = "KeyMapper",
        val restartDelayMs: Long = Constants.SERVICE_RESTART_DELAY_MS,
        val restartEnabled: Boolean = true,
    )

    companion object {
        private const val TAG = "KeyMapperKeepAlive"
        private const val TAG_ROOT = "keep-alive-config"
        private const val REQUEST_CONTENT = 2001
        private const val REQUEST_RESTART = 2002
        private const val REQUEST_CLICKER = 2003

        /** 启动保活服务（Android 8+ 走 startForegroundService）。 */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动保活服务失败：${e.message}")
            }
        }

        /** 停止保活服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止保活服务失败：${e.message}")
            }
        }

        /** 请求重启（无障碍服务 onUnbind 时调用）。 */
        fun requestRestart(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = Constants.ACTION_RESTART_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "请求重启保活服务失败：${e.message}")
            }
        }
    }
}
