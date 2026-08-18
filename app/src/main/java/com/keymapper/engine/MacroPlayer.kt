package com.keymapper.engine

import com.keymapper.domain.model.Macro
import com.keymapper.domain.model.MacroStep
import com.keymapper.domain.model.Point
import com.keymapper.domain.model.StepAction
import com.keymapper.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 宏回放器（P1-3）。
 *
 * 时序模型（Q2 决议，粒度 [Constants.MACRO_GRANULARITY_MS] = 50ms）：
 * 每个 [MacroStep] 按 `repeat` 次重复，单次执行 = 「发起动作 → 等待动作时长(holdMs) → 等待步骤间隔(delayMs)」。
 * 全程运行在协程中，不阻塞主线程；重新播放会取消上一次未完成的回放。
 *
 * 关于 KEY_DOWN / KEY_UP 的实现说明：
 * 普通应用无法向系统注入物理按键事件，因此宏里的 `key_down` 被解释为
 * 「按住该键码所绑定的屏幕坐标 holdMs 毫秒」（等价于一次长按），`key_up` 仅消耗步骤间隔。
 * 这样 "按下W → 延时 → 点击 → 松开W" 这类连招在触屏侧的表现与手指操作一致。
 */
class MacroPlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** 手势注入通道，由 [attach] 注入。 */
    private var injector: GestureInjector? = null

    /**
     * 键码 → 屏幕坐标解析器（坐标必须是**已按当前分辨率缩放**后的绝对像素）。
     * 由 [MappingEngine.loadBindings] 设置。
     */
    var keyPointResolver: ((Int) -> Point?)? = null

    private var playJob: Job? = null

    /** 当前是否正在回放。 */
    val isPlaying: Boolean
        get() = playJob?.isActive == true

    /** 绑定手势注入通道。 */
    fun attach(injector: GestureInjector) {
        this.injector = injector
    }

    /** 解绑（无障碍服务断开时调用），同时停止正在进行的回放。 */
    fun detach() {
        stop()
        injector = null
    }

    /**
     * 异步回放一个宏：会取消上一次尚未结束的回放，避免连招叠加。
     */
    fun play(macro: Macro) {
        if (macro.isEmpty) return
        playJob?.cancel()
        playJob = scope.launch { playSuspend(macro) }
    }

    /**
     * 挂起式回放（单元测试直接调用此方法，配合 `runTest` 的虚拟时间验证时序）。
     */
    suspend fun playSuspend(macro: Macro) {
        for (step in macro.steps) {
            repeat(step.safeRepeat) {
                executeStep(step)
            }
        }
    }

    /** 停止回放。 */
    fun stop() {
        playJob?.cancel()
        playJob = null
    }

    /**
     * 执行单个步骤：先发起动作，再按动作时长与步骤间隔等待。
     */
    private suspend fun executeStep(step: MacroStep) {
        when (step.action) {
            StepAction.CLICK -> {
                val x = step.x
                val y = step.y
                if (x != null && y != null) {
                    injector?.injectClick(x, y, gestureDurationOf(step))
                }
            }

            StepAction.SWIPE -> {
                val x = step.x
                val y = step.y
                val toX = step.toX
                val toY = step.toY
                if (x != null && y != null && toX != null && toY != null) {
                    injector?.injectSwipe(x, y, toX, toY, gestureDurationOf(step))
                }
            }

            StepAction.KEY_DOWN -> {
                val keyCode = step.keyCode
                val point = keyCode?.let { keyPointResolver?.invoke(it) }
                if (point != null) {
                    val duration = gestureDurationOf(step)
                    if (duration > Constants.DEFAULT_CLICK_DURATION_MS) {
                        injector?.injectLongPress(point.x, point.y, duration)
                    } else {
                        injector?.injectClick(point.x, point.y, duration)
                    }
                }
            }

            // 松开动作已由 KEY_DOWN 的 holdMs 表达，这里只消耗步骤间隔
            StepAction.KEY_UP -> Unit

            StepAction.WAIT -> Unit
        }

        // 等待动作自身时长（保证下一步不会与本步手势重叠）
        val gestureDuration = gestureDurationOf(step)
        if (gestureDuration > 0L) {
            delay(gestureDuration)
        }
        // 等待步骤间隔
        if (step.safeDelayMs > 0L) {
            delay(step.safeDelayMs)
        }
    }

    /**
     * 步骤动作自身占用的时长：显式 `holdMs` 优先，否则按动作类型取默认值。
     */
    private fun gestureDurationOf(step: MacroStep): Long {
        if (step.safeHoldMs > 0L) {
            return step.safeHoldMs.coerceAtMost(Constants.MAX_GESTURE_DURATION_MS)
        }
        return when (step.action) {
            StepAction.CLICK, StepAction.KEY_DOWN -> Constants.DEFAULT_CLICK_DURATION_MS
            StepAction.SWIPE -> Constants.DEFAULT_SWIPE_DURATION_MS
            StepAction.KEY_UP, StepAction.WAIT -> 0L
        }
    }

    /** 协程作用域是否仍存活（供服务侧健康检查）。 */
    fun isScopeActive(): Boolean = scope.isActive
}
