package com.keymapper.engine

import android.util.Log
import android.view.KeyEvent
import com.keymapper.domain.model.ActionType
import com.keymapper.domain.model.GestureType
import com.keymapper.domain.model.KeyBinding
import com.keymapper.domain.model.MappingConfig
import com.keymapper.domain.model.Point
import com.keymapper.util.Constants
import com.keymapper.util.EngineState
import com.keymapper.util.KeyEventUtil
import com.keymapper.util.ResolutionScaler
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一次绑定触发事件，供 UI（调试高亮 / 标记层）订阅。
 *
 * @property x 已按当前分辨率缩放后的屏幕 X（与实际注入坐标一致）
 * @property y 已按当前分辨率缩放后的屏幕 Y
 * @property injected 是否真的注入了手势（测试模式下为 false）
 */
data class TriggerEvent(
    val binding: KeyBinding,
    val x: Int,
    val y: Int,
    val injected: Boolean,
)

/**
 * 映射引擎（P0-3 / P0-6 / P1-2 / P1-4 / P1-5）。
 *
 * 职责：
 * 1. 持有当前生效的绑定表（单键表 + 组合键表）
 * 2. 维护组合键状态机：同时按下的键组成签名，优先匹配组合键，避免「误触单键触发组合」
 * 3. 命中后按 [ActionType] 调用 [GestureInjector] 注入手势，或交给 [MacroPlayer] 回放宏
 * 4. 绑定采集态（[EngineState.CAPTURING]）截获下一次按键回调给 UI
 * 5. 测试模式（[EngineState.TEST_MODE]）只广播高亮事件、不注入手势（P1-5）
 *
 * 线程模型：`onKeyEvent` 来自无障碍服务的回调线程（主线程），内部只做 map 查找与手势下发，
 * 耗时的宏回放交由 [MacroPlayer] 的协程执行。
 */
class MappingEngine(
    private val scaler: ResolutionScaler,
    private val macroPlayer: MacroPlayer,
) {

    /** 单键绑定表：key 为归一化后的键码。 */
    private val singleMap = HashMap<Int, KeyBinding>()

    /** 组合键绑定表：key 为 [KeyEventUtil.signature] 生成的有序签名。 */
    private val comboMap = HashMap<List<Int>, KeyBinding>()

    /** 当前按下的键码（归一化后），用于组合键状态机。 */
    private val pressedKeys = LinkedHashSet<Int>()

    /** 已被引擎消费掉 DOWN 的键码，对应的 UP 也要一并消费，避免漏传给前台应用。 */
    private val consumedKeys = HashSet<Int>()

    private val triggerListeners = CopyOnWriteArrayList<(TriggerEvent) -> Unit>()

    private var injector: GestureInjector? = null

    private var captureCallback: ((Int, List<Int>?) -> Unit)? = null

    /** 进入捕获态之前的状态，退出捕获后恢复。 */
    private var stateBeforeCapture: EngineState = EngineState.IDLE

    /** 当前引擎状态。 */
    var state: EngineState = EngineState.IDLE
        private set

    /** 当前生效配置（未缩放的原始配置）。 */
    var currentConfig: MappingConfig? = null
        private set

    /** 源分辨率（来自配置）。 */
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    /** 目标分辨率（当前屏幕），由 [setTargetResolution] 设置。 */
    private var targetWidth: Int = 0
    private var targetHeight: Int = 0

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 绑定手势注入通道（无障碍服务连接时调用）。 */
    fun attach(injector: GestureInjector) {
        this.injector = injector
        macroPlayer.attach(injector)
    }

    /** 解绑（无障碍服务断开时调用）。 */
    fun detach() {
        macroPlayer.detach()
        injector = null
        pressedKeys.clear()
        consumedKeys.clear()
    }

    /** 注入通道是否可用。 */
    fun isInjectorReady(): Boolean = injector?.isInjectorReady() == true

    /**
     * 设置目标（当前屏幕）分辨率，用于 P1-4 等比缩放。
     */
    fun setTargetResolution(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
    }

    // ------------------------------------------------------------------
    // 绑定表
    // ------------------------------------------------------------------

    /**
     * 载入一份配置的绑定（覆盖当前全部绑定）。
     *
     * @param cfg 原始配置；坐标按 [setTargetResolution] 设置的目标分辨率在触发时实时缩放
     */
    fun loadBindings(cfg: MappingConfig) {
        singleMap.clear()
        comboMap.clear()
        pressedKeys.clear()
        consumedKeys.clear()

        currentConfig = cfg
        sourceWidth = cfg.screenWidth
        sourceHeight = cfg.screenHeight

        for (binding in cfg.bindings) {
            if (!binding.isValid()) continue
            val signature = binding.signature()
            if (signature.size >= 2) {
                comboMap[signature] = binding
            } else {
                singleMap[signature.first()] = binding
            }
        }

        // 宏中的 key_down 需要按键码反查已缩放的屏幕坐标
        macroPlayer.keyPointResolver = { keyCode -> scaledPointOfKey(keyCode) }

        if (state != EngineState.TEST_MODE && state != EngineState.CAPTURING) {
            state = if (singleMap.isEmpty() && comboMap.isEmpty()) {
                EngineState.IDLE
            } else {
                EngineState.RUNNING
            }
        }
    }

    /** 清空全部绑定并回到 [EngineState.IDLE]。 */
    fun clearBindings() {
        singleMap.clear()
        comboMap.clear()
        pressedKeys.clear()
        consumedKeys.clear()
        currentConfig = null
        sourceWidth = 0
        sourceHeight = 0
        macroPlayer.keyPointResolver = null
        if (state != EngineState.CAPTURING) {
            state = EngineState.IDLE
        }
    }

    /** 当前生效绑定列表（原始坐标）。 */
    fun bindings(): List<KeyBinding> = (singleMap.values + comboMap.values).toList()

    /**
     * 当前生效绑定列表（坐标已按目标分辨率缩放），供标记层直接绘制。
     */
    fun scaledBindings(): List<KeyBinding> {
        if (!needsScaling()) return bindings()
        val scaleX = targetWidth.toDouble() / sourceWidth.toDouble()
        val scaleY = targetHeight.toDouble() / sourceHeight.toDouble()
        return bindings().map { it.scaled(scaleX, scaleY) }
    }

    /** 按签名查找绑定。 */
    fun findBinding(signature: List<Int>): KeyBinding? =
        if (signature.size >= 2) comboMap[signature] else singleMap[signature.firstOrNull() ?: -1]

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    /**
     * 无障碍服务 `onKeyEvent` 的转发入口。
     *
     * @return true 表示事件已被消费、不再传给前台应用
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP
        if (!isDown && !isUp) return false
        return handleKey(event.keyCode, isDown, event.repeatCount)
    }

    /**
     * 引擎核心：可在纯 JVM 单元测试中直接调用（不依赖 Android [KeyEvent] 实例）。
     *
     * @param keyCode Android 键码（或 [Constants.KEYCODE_WHEEL_UP] 等伪键码）
     * @param isDown true 按下 / false 抬起
     * @param repeatCount 系统长按重复计数，>0 时不重复触发绑定
     * @return 是否消费该事件
     */
    fun handleKey(keyCode: Int, isDown: Boolean, repeatCount: Int = 0): Boolean {
        val normalized = KeyEventUtil.normalize(keyCode)

        // 系统保留键一律透传，避免影响 HOME/BACK/音量等基本操作
        if (KeyEventUtil.isReserved(normalized)) return false

        if (!isDown) {
            pressedKeys.remove(normalized)
            return consumedKeys.remove(normalized)
        }

        // ---------- 捕获态（P0-3 绑定采集）----------
        if (state == EngineState.CAPTURING) {
            if (KeyEventUtil.isModifier(normalized)) {
                // 修饰键先记住，等待真正的主键
                pressedKeys.add(normalized)
                consumedKeys.add(normalized)
                return true
            }
            val modifiers = pressedKeys.filter { KeyEventUtil.isModifier(it) }
            val combination = if (modifiers.isEmpty()) {
                null
            } else {
                KeyEventUtil.signatureOf(modifiers + normalized)
            }
            val callback = captureCallback
            stopCaptureMode()
            consumedKeys.add(normalized)
            callback?.invoke(normalized, combination)
            return true
        }

        if (state == EngineState.IDLE) return false

        // 系统长按自动重复不重复触发（宏/连招由 MacroPlayer 控制节奏）
        if (repeatCount > 0) {
            return consumedKeys.contains(normalized)
        }

        pressedKeys.add(normalized)

        // ---------- 优先匹配组合键（P1-2）----------
        if (pressedKeys.size >= 2) {
            val comboSignature = KeyEventUtil.signatureOf(pressedKeys)
            comboMap[comboSignature]?.let { binding ->
                dispatch(binding)
                consumedKeys.add(normalized)
                return true
            }
        }

        // ---------- 再匹配单键：仅在没有其它键同时按下时生效 ----------
        if (pressedKeys.size == 1) {
            singleMap[normalized]?.let { binding ->
                dispatch(binding)
                consumedKeys.add(normalized)
                return true
            }
        }

        Log.d("MappingEngine", "key $normalized not matched; state=$state singles=${singleMap.keys} combos=${comboMap.keys}")
        return false
    }

    /**
     * 按单个键码匹配并派发（类图接口；也供滚轮伪键码 P2-2 与外部测试调用）。
     *
     * @return 是否命中并派发
     */
    fun matchAndDispatch(keyCode: Int): Boolean {
        val binding = singleMap[KeyEventUtil.normalize(keyCode)] ?: return false
        dispatch(binding)
        return true
    }

    // ------------------------------------------------------------------
    // 捕获态 / 测试模式
    // ------------------------------------------------------------------

    /**
     * 进入绑定采集态：截获下一次按键（含修饰键组合）后回调并自动退出。
     *
     * @param onNextKey (主键键码, 组合键签名或 null)
     */
    fun startCaptureMode(onNextKey: (Int, List<Int>?) -> Unit) {
        if (state != EngineState.CAPTURING) {
            stateBeforeCapture = state
        }
        pressedKeys.clear()
        captureCallback = onNextKey
        state = EngineState.CAPTURING
    }

    /** 退出绑定采集态，恢复此前状态。 */
    fun stopCaptureMode() {
        if (state != EngineState.CAPTURING) return
        captureCallback = null
        pressedKeys.clear()
        state = stateBeforeCapture
    }

    /** 是否处于采集态。 */
    fun isCapturing(): Boolean = state == EngineState.CAPTURING

    /**
     * 开关测试模式（P1-5）：开启后命中绑定只广播高亮事件，不注入手势。
     */
    fun setTestMode(enabled: Boolean) {
        if (state == EngineState.CAPTURING) {
            stateBeforeCapture = if (enabled) EngineState.TEST_MODE else EngineState.RUNNING
            return
        }
        state = when {
            enabled -> EngineState.TEST_MODE
            singleMap.isEmpty() && comboMap.isEmpty() -> EngineState.IDLE
            else -> EngineState.RUNNING
        }
    }

    /** 是否处于测试模式。 */
    fun isTestMode(): Boolean = state == EngineState.TEST_MODE

    // ------------------------------------------------------------------
    // 触发监听
    // ------------------------------------------------------------------

    /** 注册触发监听（调试高亮、标记层反馈）。 */
    fun addTriggerListener(listener: (TriggerEvent) -> Unit) {
        triggerListeners.addIfAbsent(listener)
    }

    /** 注销触发监听。 */
    fun removeTriggerListener(listener: (TriggerEvent) -> Unit) {
        triggerListeners.remove(listener)
    }

    // ------------------------------------------------------------------
    // 派发
    // ------------------------------------------------------------------

    /**
     * 派发一条绑定：先算出目标屏幕坐标，再按类型注入手势 / 回放宏。
     */
    private fun dispatch(binding: KeyBinding) {
        val point = scaledPoint(binding.x, binding.y)
        Log.d("MappingEngine", "dispatch ${binding.displayKey()} src=(${binding.x},${binding.y}) scaled=($point) source=${sourceWidth}x$sourceHeight target=${targetWidth}x$targetHeight")
        val testMode = state == EngineState.TEST_MODE
        val injectorRef = injector

        var injected = false
        if (!testMode && injectorRef != null) {
            injected = if (binding.hasMacro) {
                playMacro(binding)
            } else {
                injectGesture(binding, point, injectorRef)
            }
        }

        val event = TriggerEvent(binding = binding, x = point.x, y = point.y, injected = injected)
        for (listener in triggerListeners) {
            listener(event)
        }
    }

    /** 回放绑定上的宏（坐标先按当前分辨率缩放）。 */
    private fun playMacro(binding: KeyBinding): Boolean {
        val macro = binding.macro ?: return false
        val scaled = if (needsScaling()) {
            macro.scaled(
                targetWidth.toDouble() / sourceWidth.toDouble(),
                targetHeight.toDouble() / sourceHeight.toDouble(),
            )
        } else {
            macro
        }
        macroPlayer.play(scaled)
        return true
    }

    /** 按 [ActionType] 注入具体手势。 */
    private fun injectGesture(
        binding: KeyBinding,
        point: Point,
        injector: GestureInjector,
    ): Boolean {
        val gesture = binding.effectiveGesture()
        val duration = gesture?.effectiveDurationMs() ?: Constants.DEFAULT_CLICK_DURATION_MS

        return when (gesture?.type ?: GestureType.CLICK) {
            GestureType.CLICK -> injector.injectClick(point.x, point.y, duration)

            GestureType.LONG_PRESS -> injector.injectLongPress(point.x, point.y, duration)

            GestureType.SWIPE -> {
                val fromRaw = gesture?.from
                val toRaw = gesture?.to
                val from = if (fromRaw != null) scaledPoint(fromRaw.x, fromRaw.y) else point
                if (toRaw == null) {
                    // 缺少终点时退化为点击，避免无效手势
                    injector.injectClick(point.x, point.y, Constants.DEFAULT_CLICK_DURATION_MS)
                } else {
                    val to = scaledPoint(toRaw.x, toRaw.y)
                    injector.injectSwipe(from.x, from.y, to.x, to.y, duration)
                }
            }

            GestureType.PINCH -> {
                val focalRaw = gesture?.focal
                val focal = if (focalRaw != null) scaledPoint(focalRaw.x, focalRaw.y) else point
                val radius = gesture?.pinchRadius ?: 240
                val scaledRadius = if (needsScaling()) {
                    scaler.scaleValue(radius, sourceWidth, targetWidth)
                } else {
                    radius
                }
                injector.injectPinch(
                    focalX = focal.x,
                    focalY = focal.y,
                    scaleFactor = gesture?.scaleFactor ?: 1.0f,
                    durationMs = duration,
                    radius = scaledRadius,
                )
            }
        }
    }

    /** 键码 → 已缩放屏幕坐标（供 [MacroPlayer] 的 key_down 步骤反查）。 */
    private fun scaledPointOfKey(keyCode: Int): Point? {
        val binding = singleMap[KeyEventUtil.normalize(keyCode)] ?: return null
        return scaledPoint(binding.x, binding.y)
    }

    /** 源坐标 → 当前屏幕坐标（P1-4）。 */
    private fun scaledPoint(x: Int, y: Int): Point {
        if (!needsScaling()) return Point(x, y)
        return scaler.scalePoint(x, y, sourceWidth, sourceHeight, targetWidth, targetHeight)
    }

    /** 是否需要缩放。 */
    private fun needsScaling(): Boolean =
        scaler.needsScaling(sourceWidth, sourceHeight, targetWidth, targetHeight)

    /** 重置按键状态机（切换应用/配置时调用，避免残留按下状态）。 */
    fun resetKeyState() {
        pressedKeys.clear()
        consumedKeys.clear()
    }
}
