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
import com.keymapper.engine.AutoClickerPlayer
import com.keymapper.engine.MappingEngine
import com.keymapper.engine.TriggerEvent
import com.keymapper.ui.clicker.AutoClickerActivity
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
    private val autoClicker: AutoClickerPlayer by inject()

    private lateinit var windowManager: WindowManager

    private var ballView: FloatingButtonView? = null
    private var panelView: ControlPanelView? = null
    private var markerView: MarkerLayerView? = null
    private var debugView: DebugHighlightView? = null
    private var clickerStopView: android.widget.Button? = null

    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var debugParams: WindowManager.LayoutParams? = null
    private var clickerStopParams: WindowManager.LayoutParams? = null

    private var ballAdded = false
    private var panelAdded = false
    private var markerAdded = false
    private var debugAdded = false
    private var clickerStopAdded = false

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

    // 连点器运行状态监听：显示/隐藏「■ 停止连点」小按钮
    private val clickerStateListener: (Boolean) -> Unit = { running ->
        if (running) showClickerStop() else hideClickerStop()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        markersVisible = repository.isMarkersVisible()
        engine.addTriggerListener(triggerListener)
        // 连点器运行状态：显示/隐藏「■ 停止连点」小按钮（服务启动时已在连点则立即显示）
        autoClicker.addStateListener(clickerStateListener)
        if (autoClicker.isRunning) {
            showClickerStop()
        }
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
        autoClicker.removeStateListener(clickerStateListener)
        engine.removeTriggerListener(triggerListener)
        unregisterReceiverCompat()
        removeAllViews()
        ballAdded = false
        panelAdded = false
        markerAdded = false
        debugAdded = false
        clickerStopAdded = false
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
            view.onOpenClicker = { launchActivity(AutoClickerActivity::class.java) }
            view.onOpenSettings = { launchActivity(SettingsActivity::class.java) }
            view.onClose = { hidePanel() }
            view.setActiveConfig(repository.activeConfigName())
            view.setMarkerVisibleState(markersVisible)
        }

        // 标记层（全屏、不可触摸）
        markerParams = fullscreenParams(touchable = false)
        markerView = MarkerLayerView(this).also { view ->
            view.onMarkerLongPress = { marker -> showMarkerMenu(marker) }
            view.onMarkerDrag = { marker, x, y -> updateBindingCoordinate(marker, x, y) }
        }

        // 调试高亮层（全屏、不可触摸，仅测试模式挂载）
        debugParams = fullscreenParams(touchable = false)
        debugView = DebugHighlightView(this)

        // 连点器「■ 停止」小按钮（初始不挂载，连点开始时显示）
        clickerStopParams = overlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.5f).toInt().coerceAtLeast(0)
            y = (dm.heightPixels * 0.8f).toInt().coerceAtLeast(0)
        }
        clickerStopView = android.widget.Button(this).apply {
            text = getString(R.string.clicker_floating_stop)
            textSize = 13f
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CCD32F2F"))
            setOnClickListener { autoClicker.stop() }
        }
        // 服务启动时连点器已在运行：立即显示停止按钮
        if (autoClicker.isRunning) {
            showClickerStop()
        }

        // 让标记层/调试层从屏幕左上角开始绘制，避免被刘海/状态栏顶下来
        markerParams?.gravity = Gravity.TOP or Gravity.START
        debugParams?.gravity = Gravity.TOP or Gravity.START

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
        panelView?.setMarkerVisibleState(markersVisible)
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
        panelView?.setMarkerVisibleState(visible)
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
        val cfg = engine.currentConfig
        markerView?.snapToGrid = cfg?.snapToGrid == true
        markerView?.gridSize = cfg?.gridSize ?: 0
        markerView?.setMarkers(engine.scaledBindings())
    }

    private fun showMarkerMenu(binding: KeyBinding) {
        val names = arrayOf(
            getString(R.string.mapping_marker_delete),
            getString(R.string.mapping_marker_rebind),
            getString(R.string.mapping_marker_size),
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
                    2 -> showMarkerSizeDialog(binding)
                    3 -> setMarkerManageMode(false)
                }
                d.dismiss()
            }
            .create()
        showOverlayDialog(dialog)
    }

    /**
     * 拖动微调：更新生效配置中某绑定的坐标并持久化（坐标已在标记层内按网格吸附）。
     */
    private fun updateBindingCoordinate(binding: KeyBinding, x: Int, y: Int) {
        val name = repository.activeConfigName() ?: return
        scope.launch {
            try {
                val cfg = repository.loadConfig(name) ?: return@launch
                val signature = binding.signature()
                val updated = cfg.copy(
                    bindings = cfg.bindings.map {
                        if (it.signature() == signature) it.copy(x = x, y = y) else it
                    },
                )
                repository.saveConfig(updated)
                repository.setActiveConfigName(name)
                engine.loadBindings(updated)
                refreshMarkers()
                sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
            } catch (e: Exception) {
                Log.w(TAG, "拖动微调保存失败：${e.message}")
            }
        }
    }

    /** 运行态调整标记大小：SeekBar 对话框，实时写回生效配置。 */
    private fun showMarkerSizeDialog(binding: KeyBinding) {
        val context = this
        val container = android.widget.LinearLayout(context).apply {
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val label = android.widget.TextView(context).apply {
            text = getString(R.string.mapping_marker_size_value, binding.effectiveRadiusDp())
        }
        val seek = android.widget.SeekBar(context).apply {
            max = 40
            progress = binding.effectiveRadiusDp().coerceIn(4, 40)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = getString(R.string.mapping_marker_size_value, value.coerceAtLeast(4))
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
            })
        }
        container.addView(label)
        container.addView(seek)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapping_marker_size))
            .setView(container)
            .setPositiveButton(R.string.ok) { d, _ ->
                val radius = seek.progress.coerceAtLeast(4)
                updateBindingRadius(binding, radius)
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        showOverlayDialog(dialog)
    }

    /** 把新的标记半径写回生效配置并刷新标记层。 */
    private fun updateBindingRadius(binding: KeyBinding, radius: Int) {
        val name = repository.activeConfigName() ?: return
        scope.launch {
            try {
                val cfg = repository.loadConfig(name) ?: return@launch
                val signature = binding.signature()
                val updated = cfg.copy(
                    bindings = cfg.bindings.map {
                        if (it.signature() == signature) it.copy(radius = radius) else it
                    },
                )
                repository.saveConfig(updated)
                repository.setActiveConfigName(name)
                engine.loadBindings(updated)
                refreshMarkers()
                sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
            } catch (e: Exception) {
                Log.w(TAG, "调整标记大小保存失败：${e.message}")
            }
        }
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
        if (name.isNullOrBlank()) {
            // 无生效配置（如刚删除了当前配置）：必须清空引擎绑定并刷新标记层，
            // 否则被删配置的映射会残留在屏幕上、且下次打开「没选就出来」
            engine.clearBindings()
            refreshMarkers()
            panelView?.setActiveConfig(null)
            sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
            return
        }
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
        clickerStopView?.let { safeRemove(it) }
    }

    // ------------------------------------------------------------------
    // 连点器「■ 停止」小按钮（模式二·屏幕辅助）
    // ------------------------------------------------------------------

    /** 显示连点器停止按钮（悬浮小按钮，不遮挡游戏主要区域）。 */
    private fun showClickerStop() {
        if (clickerStopAdded || clickerStopView == null) return
        clickerStopView?.let {
            windowManager.addView(it, clickerStopParams)
            clickerStopAdded = true
        }
    }

    private fun hideClickerStop() {
        if (!clickerStopAdded) return
        clickerStopView?.let { safeRemove(it) }
        clickerStopAdded = false
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
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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
