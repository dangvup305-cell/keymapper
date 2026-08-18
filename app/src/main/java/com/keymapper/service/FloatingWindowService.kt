package com.keymapper.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.keymapper.R
import com.keymapper.data.ConfigRepository
import com.keymapper.domain.model.KeyBinding
import com.keymapper.engine.MappingEngine
import com.keymapper.engine.TriggerEvent
import com.keymapper.ui.config.ConfigPanelActivity
import com.keymapper.ui.debug.DebugHighlightView
import com.keymapper.ui.floating.ControlPanelView
import com.keymapper.ui.floating.FloatingButtonView
import com.keymapper.ui.floating.MarkerLayerView
import com.keymapper.ui.settings.SettingsActivity
import com.keymapper.util.Constants
import com.keymapper.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 悬浮窗服务（P0-1 / P0-4 / P1-5）。
 *
 * 通过 `WindowManager` + `TYPE_APPLICATION_OVERLAY` 在任意前台应用之上挂载：
 * - [FloatingButtonView]     可拖拽悬浮球（收起态）
 * - [ControlPanelView]       展开操作面板（开始映射 / 加载配置 / 标记 / 设置）
 * - [MarkerLayerView]        跨应用映射标记层（不可触摸，管理态可长按）
 * - [DebugHighlightView]     测试模式按键高亮层（P1-5）
 *
 * 与引擎 / 仓库通过 Koin 单例 + [Constants] 中的广播 Action 解耦，不直接持有 Activity 引用。
 */
class FloatingWindowService : Service() {

    private val engine: MappingEngine by inject()
    private val repository: ConfigRepository by inject()

    private lateinit var windowManager: WindowManager

    private var ballView: FloatingButtonView? = null
    private var panelView: ControlPanelView? = null
    private var markerView: MarkerLayerView? = null
    private var debugView: DebugHighlightView? = null

    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var debugParams: WindowManager.LayoutParams? = null

    private var ballAdded = false
    private var panelAdded = false
    private var markerAdded = false
    private var debugAdded = false

    private var markersVisible: Boolean = true
    private var testMode: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // 由 MainActivity / 外部触发：先收起悬浮球，再拉起映射覆盖层
                Constants.ACTION_START_MAPPING -> startMapping()
                Constants.ACTION_HIDE_FLOATING -> hideFloating()
                Constants.ACTION_STOP_MAPPING,
                Constants.ACTION_SHOW_FLOATING,
                -> showFloating()

                Constants.ACTION_SWITCH_CONFIG -> {
                    val name = intent.getStringExtra(Constants.EXTRA_KEYMAPPER_CONFIG_NAME)
                    applyConfig(name)
                }

                Constants.ACTION_CONFIG_CHANGED -> applyConfig(repository.activeConfigName())
                Constants.ACTION_BINDINGS_UPDATED -> refreshMarkers()

                Constants.ACTION_SHOW_MARKERS -> setMarkersVisible(true)
                Constants.ACTION_HIDE_MARKERS -> setMarkersVisible(false)

                Constants.ACTION_TEST_MODE_ON -> setTestMode(true)
                Constants.ACTION_TEST_MODE_OFF -> setTestMode(false)

                Constants.ACTION_LOW_BATTERY -> {
                    val enabled = intent.getBooleanExtra(Constants.EXTRA_KEYMAPPER_ENABLED, false)
                    applyLowBattery(enabled)
                }

                Constants.ACTION_OPEN_CONFIG_PANEL -> launchActivity(ConfigPanelActivity::class.java)
                Constants.ACTION_OPEN_SETTINGS -> launchActivity(SettingsActivity::class.java)
            }
        }
    }

    private var receiverRegistered = false

    // 引擎触发监听：测试模式下把命中事件高亮出来（P1-5）
    private val triggerListener: (TriggerEvent) -> Unit = { event ->
        if (testMode) {
            debugView?.highlight(event.x, event.y, event.binding.labelColor, event.binding.displayKey())
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        markersVisible = repository.isMarkersVisible()
        engine.addTriggerListener(triggerListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 没有悬浮窗权限时无法挂载，直接退出，避免崩溃
        if (!PermissionHelper.canDrawOverlays(this)) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，悬浮窗服务退出")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ballAdded) {
            setupViews()
            registerReceiverCompat()
            isRunning = true
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        engine.removeTriggerListener(triggerListener)
        unregisterReceiverCompat()
        removeAllViews()
        ballAdded = false
        panelAdded = false
        markerAdded = false
        debugAdded = false
        testMode = false
        isRunning = false
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 视图构建
    // ------------------------------------------------------------------

    private fun setupViews() {
        val dm = resources.displayMetrics
        val initX = (dm.widthPixels - 140).coerceAtLeast(0)
        val initY = (dm.heightPixels - 260).coerceAtLeast(0)

        // 悬浮球
        ballParams = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }
        ballView = FloatingButtonView(this).also { view ->
            view.onSingleTap = { showPanel() }
            view.onLongPress = { hideFloating() }
            view.onDragDelta = { dx, dy -> moveBall(dx, dy) }
            view.onDragEnd = { /* 吸边等增强可在此扩展 */ }
        }
        windowManager.addView(ballView, ballParams)
        ballAdded = true

        // 操作面板（初始不挂到窗口，展开时再 add）
        panelParams = overlayParams(
            width = (dm.widthPixels * 0.8f).toInt().coerceAtLeast(260),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.1f).toInt().coerceAtLeast(0)
            y = (dm.heightPixels * 0.2f).toInt().coerceAtLeast(0)
        }
        panelView = ControlPanelView(this).also { view ->
            view.onStartMapping = { startMapping() }
            view.onLoadConfig = { showQuickSwitch() }
            view.onToggleMarkers = { setMarkersVisible(!markersVisible) }
            view.onToggleMarkerManage = { setMarkerManageMode(true) }
            view.onOpenSettings = { launchActivity(SettingsActivity::class.java) }
            view.onClose = { hidePanel() }
            view.setActiveConfig(repository.activeConfigName())
        }

        // 标记层（全屏、不可触摸）
        markerParams = fullscreenParams(touchable = false)
        markerView = MarkerLayerView(this).also { view ->
            view.onMarkerLongPress = { marker -> showMarkerMenu(marker) }
        }

        // 调试高亮层（全屏、不可触摸，仅测试模式挂载）
        debugParams = fullscreenParams(touchable = false)
        debugView = DebugHighlightView(this)

        if (markersVisible) {
            windowManager.addView(markerView, markerParams)
            markerAdded = true
            refreshMarkers()
        }
        val active = repository.activeConfigName()
        if (!active.isNullOrBlank()) {
            applyConfig(active)
        }
    }

    // ------------------------------------------------------------------
    // 悬浮球 / 面板 显隐与拖拽
    // ------------------------------------------------------------------

    private fun showFloating() {
        if (!ballAdded) return
        ballView?.visibility = android.view.View.VISIBLE
        hidePanel()
    }

    private fun hideFloating() {
        ballView?.visibility = android.view.View.GONE
        hidePanel()
    }

    private fun showPanel() {
        if (panelAdded) {
            panelView?.visibility = android.view.View.VISIBLE
            return
        }
        panelView?.let {
            windowManager.addView(it, panelParams)
            panelAdded = true
        }
    }

    private fun hidePanel() {
        if (!panelAdded) return
        panelView?.let { windowManager.removeView(it) }
        panelAdded = false
    }

    private fun moveBall(dx: Int, dy: Int) {
        val params = ballParams ?: return
        params.x += dx
        params.y += dy
        ballView?.let { windowManager.updateViewLayout(it, params) }
    }

    // ------------------------------------------------------------------
    // 标记层
    // ------------------------------------------------------------------

    private fun setMarkersVisible(visible: Boolean) {
        markersVisible = visible
        repository.setMarkersVisible(visible)
        if (visible) {
            if (!markerAdded) {
                markerView?.let {
                    windowManager.addView(it, markerParams)
                    markerAdded = true
                }
            }
            refreshMarkers()
        } else if (markerAdded) {
            markerView?.let { windowManager.removeView(it) }
            markerAdded = false
        }
    }

    private fun setMarkerManageMode(manage: Boolean) {
        markerView?.manageMode = manage
        // 管理态需要标记层可接收触摸
        if (markerAdded) {
            markerParams?.flags = fullscreenParams(touchable = manage).flags
            markerView?.let { windowManager.updateViewLayout(it, markerParams) }
        }
    }

    private fun refreshMarkers() {
        if (!markerAdded) return
        markerView?.setMarkers(engine.scaledBindings())
    }

    private fun showMarkerMenu(binding: KeyBinding) {
        val names = arrayOf(
            getString(R.string.mapping_marker_delete),
            getString(R.string.mapping_marker_rebind),
            getString(R.string.close),
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapping_marker_menu_title, binding.displayKey()))
            .setItems(names) { d, which ->
                when (which) {
                    0 -> deleteBinding(binding)
                    1 -> {
                        setMarkerManageMode(false)
                        startMapping()
                    }
                    2 -> setMarkerManageMode(false)
                }
                d.dismiss()
            }
            .create()
        showOverlayDialog(dialog)
    }

    /**
     * 在 Service 上下文中安全弹出对话框。
     *
     * Service 没有 Activity 窗口 token，直接 `show()` 会抛 `BadTokenException`；
     * 这里把窗口类型改为悬浮窗类型（已持有 `SYSTEM_ALERT_WINDOW` 权限），即可脱离 Activity 显示。
     */
    private fun showOverlayDialog(dialog: AlertDialog) {
        try {
            dialog.window?.setType(overlayType())
            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "悬浮对话框显示失败：${e.message}")
        }
    }

    private fun deleteBinding(binding: KeyBinding) {
        val name = repository.activeConfigName() ?: return
        scope.launch {
            try {
                val cfg = repository.loadConfig(name) ?: return@launch
                val updated = cfg.removeBinding(binding.signature())
                repository.saveConfig(updated)
                repository.setActiveConfigName(name)
                engine.loadBindings(updated)
                refreshMarkers()
                sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
            } catch (e: Exception) {
                Log.w(TAG, "删除绑定失败：${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 测试模式（P1-5）
    // ------------------------------------------------------------------

    private fun setTestMode(enabled: Boolean) {
        testMode = enabled
        engine.setTestMode(enabled)
        if (enabled) {
            if (!debugAdded) {
                debugView?.let {
                    windowManager.addView(it, debugParams)
                    debugAdded = true
                }
            }
        } else if (debugAdded) {
            debugView?.clear()
            debugView?.let { windowManager.removeView(it) }
            debugAdded = false
        }
    }

    private fun applyLowBattery(enabled: Boolean) {
        if (enabled) {
            debugView?.frameIntervalMs = Constants.LOW_BATTERY_HIGHLIGHT_FRAME_MS
            ballView?.setIdleAlpha(0.6f)
        } else {
            debugView?.frameIntervalMs = Constants.NORMAL_HIGHLIGHT_FRAME_MS
            ballView?.setIdleAlpha(1.0f)
        }
    }

    // ------------------------------------------------------------------
    // 配置应用（切换 / 变更）
    // ------------------------------------------------------------------

    private fun applyConfig(name: String?) {
        if (name.isNullOrBlank()) return
        scope.launch {
            try {
                val cfg = repository.loadConfig(name) ?: return@launch
                engine.loadBindings(cfg)
                repository.setActiveConfigName(name)
                panelView?.setActiveConfig(name)
                refreshMarkers()
                sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
            } catch (e: Exception) {
                Log.w(TAG, "应用配置失败：${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 交互跳转
    // ------------------------------------------------------------------

    private fun startMapping() {
        // 隐藏悬浮球，避免遮挡映射覆盖层
        hideFloating()
        launchActivity(com.keymapper.ui.mapping.MappingOverlayActivity::class.java)
    }

    private fun showQuickSwitch() {
        scope.launch {
            val names = repository.listConfigs()
            if (names.isEmpty()) {
                android.os.Handler(mainLooper).post {
                    android.widget.Toast.makeText(
                        this@FloatingWindowService,
                        R.string.floating_no_config,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            android.os.Handler(mainLooper).post {
                val dialog = AlertDialog.Builder(this@FloatingWindowService)
                    .setTitle(R.string.floating_quick_switch_title)
                    .setItems(names.toTypedArray()) { d, which ->
                        applyConfig(names[which])
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                showOverlayDialog(dialog)
            }
        }
    }

    private fun launchActivity(cls: Class<*>) {
        val intent = Intent(this, cls).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "启动 Activity 失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 广播注册
    // ------------------------------------------------------------------

    private fun registerReceiverCompat() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_START_MAPPING)
            addAction(Constants.ACTION_STOP_MAPPING)
            addAction(Constants.ACTION_SHOW_FLOATING)
            addAction(Constants.ACTION_HIDE_FLOATING)
            addAction(Constants.ACTION_SWITCH_CONFIG)
            addAction(Constants.ACTION_CONFIG_CHANGED)
            addAction(Constants.ACTION_BINDINGS_UPDATED)
            addAction(Constants.ACTION_SHOW_MARKERS)
            addAction(Constants.ACTION_HIDE_MARKERS)
            addAction(Constants.ACTION_TEST_MODE_ON)
            addAction(Constants.ACTION_TEST_MODE_OFF)
            addAction(Constants.ACTION_LOW_BATTERY)
            addAction(Constants.ACTION_OPEN_CONFIG_PANEL)
            addAction(Constants.ACTION_OPEN_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiverCompat() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(receiver)
        } catch (ignored: Exception) {
            // 未注册成功时忽略
        }
        receiverRegistered = false
    }

    private fun removeAllViews() {
        ballView?.let { safeRemove(it) }
        panelView?.let { safeRemove(it) }
        markerView?.let { safeRemove(it) }
        debugView?.let { safeRemove(it) }
    }

    private fun safeRemove(view: android.view.View) {
        try {
            windowManager.removeView(view)
        } catch (ignored: Exception) {
            // 视图未挂到窗口时忽略
        }
    }

    // ------------------------------------------------------------------
    // WindowManager.LayoutParams 构造
    // ------------------------------------------------------------------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        // 悬浮球/面板需要可触摸但不抢占前台应用焦点
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(width, height, overlayType(), flags, PixelFormat.TRANSLUCENT)
    }

    private fun fullscreenParams(touchable: Boolean): WindowManager.LayoutParams {
        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    companion object {
        private const val TAG = "KeyMapperFloating"

        /** 服务是否正在运行（供 MainActivity 判断开关状态）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * 启动悬浮窗服务。
         *
         * 注意：本服务**不是**前台服务（前台通知由 [KeepAliveService] 统一持有），
         * 因此必须用 `startService`。若误用 `startForegroundService` 而不调用
         * `startForeground()`，Android 8+ 会在 5 秒后抛
         * `ForegroundServiceDidNotStartInTimeException` 导致崩溃。
         *
         * 调用方需处于前台（Activity）或进程已持有前台服务，否则 Android 8+ 的
         * 后台启动限制会拒绝本次启动。
         */
        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "启动悬浮窗服务失败：${e.message}")
            }
        }

        /** 停止悬浮窗服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatingWindowService::class.java))
            } catch (ignored: Exception) {
                // 停止失败不影响主流程
            }
        }
    }
}
