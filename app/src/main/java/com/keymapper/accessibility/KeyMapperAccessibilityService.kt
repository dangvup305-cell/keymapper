package com.keymapper.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.GestureResultCallback
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.keymapper.data.ConfigRepository
import com.keymapper.engine.GestureInjector
import com.keymapper.engine.MappingEngine
import com.keymapper.service.KeepAliveService
import com.keymapper.util.Constants
import com.keymapper.util.ResolutionScaler
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 无障碍服务：本工具的系统能力核心（P0-6 / P1-1 / P1-2 / P2-1）。
 *
 * 双职责（ARCHITECTURE 1.4）：
 * 1. **全局键盘监听**：[onKeyEvent] 在任意前台应用下都会收到物理按键（HOME/BACK/RECENTS 等系统保留键除外），
 *    事件转发给已注册的监听者，[MappingEngine] 是默认监听者。
 * 2. **手势注入**：实现 [GestureInjector]，把点击/长按/滑动/双指缩放解析为 `Path` + `StrokeDescription`
 *    通过父类的 `dispatchGesture` 注入系统触摸。
 *
 * 另外在 [onAccessibilityEvent] 中读取前台应用包名，支撑 P1-1「按包名自动切换配置」。
 */
class KeyMapperAccessibilityService : AccessibilityService(), GestureInjector {

    private val engine: MappingEngine by inject()
    private val repository: ConfigRepository by inject()
    private val scaler: ResolutionScaler by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 按键监听者：返回 true 表示消费事件。 */
    private val keyListeners = CopyOnWriteArrayList<(KeyEvent) -> Boolean>()

    /** 引擎监听者引用，便于 onUnbind 时注销。 */
    private val engineKeyListener: (KeyEvent) -> Boolean = { event -> engine.onKeyEvent(event) }

    /** 当前屏幕真实分辨率，随配置变化刷新。 */
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    /** 最近一次自动切换命中的包名，避免同一应用重复加载。 */
    private var lastAutoSwitchedPackage: String? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = this
        connectedState.postValue(true)

        refreshScreenSize()

        engine.attach(this)
        registerKeyListener(engineKeyListener)

        // 恢复上次生效的配置，保证服务重启后按键立即可用
        serviceScope.launch {
            try {
                val active = repository.loadActiveConfig()
                if (active != null) {
                    engine.loadBindings(active)
                    sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
                }
            } catch (e: Exception) {
                Log.w(TAG, "恢复生效配置失败：${e.message}")
            }
        }

        // 与前台保活服务形成闭环
        KeepAliveService.start(this)
        Log.i(TAG, "无障碍服务已连接，屏幕分辨率 ${screenWidth}x$screenHeight")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        for (listener in keyListeners) {
            try {
                if (listener(event)) return true
            } catch (e: Exception) {
                Log.w(TAG, "按键监听者异常：${e.message}")
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        if (pkg == foregroundPackageState.value) return

        foregroundPackageState.postValue(pkg)
        // 切换应用时清掉按键状态机，避免残留的"按下"状态误判组合键
        engine.resetKeyState()

        if (repository.isAutoSwitchEnabled()) {
            autoSwitchConfigFor(pkg)
        }
    }

    override fun onInterrupt() {
        // 系统要求中断反馈：本服务无语音/振动反馈，仅复位按键状态
        engine.resetKeyState()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "无障碍服务 onUnbind，准备释放并触发保活")
        teardown()
        // 交由保活服务尝试重新拉起并引导用户确认开关（Q1 决议）
        KeepAliveService.requestRestart(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 统一释放逻辑，onUnbind/onDestroy 都可能触发，需保证幂等。 */
    private fun teardown() {
        unregisterKeyListener(engineKeyListener)
        engine.detach()
        if (instanceRef === this) {
            instanceRef = null
        }
        connectedState.postValue(false)
    }

    // ------------------------------------------------------------------
    // 按键监听注册
    // ------------------------------------------------------------------

    /** 注册按键监听者（返回 true 表示消费事件）。 */
    fun registerKeyListener(listener: (KeyEvent) -> Boolean) {
        keyListeners.addIfAbsent(listener)
    }

    /** 注销按键监听者。 */
    fun unregisterKeyListener(listener: (KeyEvent) -> Boolean) {
        keyListeners.remove(listener)
    }

    // ------------------------------------------------------------------
    // P1-1 按前台包名自动切换配置
    // ------------------------------------------------------------------

    private fun autoSwitchConfigFor(pkg: String) {
        if (pkg == lastAutoSwitchedPackage) return
        serviceScope.launch {
            try {
                val cfg = repository.findConfigForPackage(pkg) ?: return@launch
                lastAutoSwitchedPackage = pkg
                repository.setActiveConfigName(cfg.configName)
                refreshScreenSize()
                engine.loadBindings(cfg)
                sendBroadcast(Intent(Constants.ACTION_BINDINGS_UPDATED).setPackage(packageName))
                Log.i(TAG, "前台应用 $pkg 命中配置「${cfg.configName}」并已加载")
            } catch (e: Exception) {
                Log.w(TAG, "自动切换配置失败：${e.message}")
            }
        }
    }

    /** 刷新屏幕真实分辨率，并同步给引擎（P1-4 缩放基准）。 */
    private fun refreshScreenSize() {
        val (w, h) = ResolutionScaler.realScreenSize(this)
        screenWidth = w
        screenHeight = h
        engine.setTargetResolution(w, h)
    }

    // ------------------------------------------------------------------
    // GestureInjector 实现（手势注入）
    // ------------------------------------------------------------------

    override fun isInjectorReady(): Boolean = instanceRef === this

    override fun injectClick(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(clampX(x), clampY(y)) }
        return dispatchStrokes(listOf(strokeOf(path, durationMs)))
    }

    override fun injectLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(clampX(x), clampY(y)) }
        return dispatchStrokes(listOf(strokeOf(path, durationMs)))
    }

    override fun injectSwipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(clampX(fromX), clampY(fromY))
            lineTo(clampX(toX), clampY(toY))
        }
        return dispatchStrokes(listOf(strokeOf(path, durationMs)))
    }

    override fun injectPinch(
        focalX: Int,
        focalY: Int,
        scaleFactor: Float,
        durationMs: Long,
        radius: Int,
    ): Boolean {
        val safeFactor = if (scaleFactor <= 0f) 1f else scaleFactor
        val startRadius = radius.coerceAtLeast(20).toFloat()
        val endRadius = (startRadius * safeFactor).coerceAtLeast(20f)

        val cx = clampX(focalX)
        val cy = clampY(focalY)

        // 第一根手指：焦点左上方向；第二根：焦点右下方向（对角线扩张/收缩最稳定）
        val first = Path().apply {
            moveTo(clampXf(cx - startRadius), clampYf(cy - startRadius))
            lineTo(clampXf(cx - endRadius), clampYf(cy - endRadius))
        }
        val second = Path().apply {
            moveTo(clampXf(cx + startRadius), clampYf(cy + startRadius))
            lineTo(clampXf(cx + endRadius), clampYf(cy + endRadius))
        }
        return dispatchStrokes(
            listOf(strokeOf(first, durationMs), strokeOf(second, durationMs)),
        )
    }

    /** 构造一条 Stroke，时长做安全范围收敛。 */
    private fun strokeOf(path: Path, durationMs: Long): GestureDescription.StrokeDescription {
        val duration = durationMs.coerceIn(1L, Constants.MAX_GESTURE_DURATION_MS)
        return GestureDescription.StrokeDescription(path, 0L, duration)
    }

    /**
     * 组装并注入手势。
     *
     * @return 系统是否接受了本次手势派发
     */
    private fun dispatchStrokes(strokes: List<GestureDescription.StrokeDescription>): Boolean {
        if (strokes.isEmpty()) return false
        return try {
            val builder = GestureDescription.Builder()
            for (stroke in strokes) {
                builder.addStroke(stroke)
            }
            dispatchGesture(builder.build(), gestureCallback, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "手势注入失败：${e.message}")
            false
        }
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            // 正常完成，无需处理
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Log.d(TAG, "手势被系统取消（可能被前台应用抢占触摸）")
        }
    }

    // ------------------------------------------------------------------
    // 坐标裁剪：超出屏幕的坐标会让 StrokeDescription 抛异常
    // ------------------------------------------------------------------

    private fun clampX(x: Int): Float = clampXf(x.toFloat())

    private fun clampY(y: Int): Float = clampYf(y.toFloat())

    private fun clampXf(x: Float): Float {
        if (screenWidth <= 0) return x.coerceAtLeast(0f)
        return x.coerceIn(0f, (screenWidth - 1).toFloat())
    }

    private fun clampYf(y: Float): Float {
        if (screenHeight <= 0) return y.coerceAtLeast(0f)
        return y.coerceIn(0f, (screenHeight - 1).toFloat())
    }

    companion object {
        private const val TAG = "KeyMapperA11y"

        @Volatile
        private var instanceRef: KeyMapperAccessibilityService? = null

        private val connectedState = MutableLiveData<Boolean>(false)

        private val foregroundPackageState = MutableLiveData<String>("")

        /** 服务单例，未连接时为 null。 */
        val instance: KeyMapperAccessibilityService?
            get() = instanceRef

        /** 服务是否已连接。 */
        fun isRunning(): Boolean = instanceRef != null

        /** 无障碍服务连接状态（供 UI 观察）。 */
        val connected: LiveData<Boolean> get() = connectedState

        /** 当前前台应用包名（供 UI / 自动切换观察）。 */
        val foregroundPackage: LiveData<String> get() = foregroundPackageState
    }
}
