package com.keymapper.domain.model

import com.keymapper.util.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 宏步骤动作（P1-3）。
 *
 * - [KEY_DOWN] 触发某个键码已绑定的坐标，并按住 [MacroStep.holdMs]
 * - [KEY_UP]   松开标记（按住时长已由 KEY_DOWN 的 holdMs 表达，此步骤只消耗 delayMs）
 * - [CLICK]    直接点击 (x, y)
 * - [SWIPE]    从 (x, y) 滑到 (toX, toY)
 * - [WAIT]     纯等待 delayMs
 */
@Serializable
enum class StepAction {
    @SerialName("key_down")
    KEY_DOWN,

    @SerialName("key_up")
    KEY_UP,

    @SerialName("click")
    CLICK,

    @SerialName("swipe")
    SWIPE,

    @SerialName("wait")
    WAIT,
}

/**
 * 单个宏步骤（P1-2 / P1-3）。
 *
 * 时序语义（Q2 决议，粒度 50ms）：
 * 1. 执行动作（点击 / 滑动 / 按住绑定坐标 holdMs / 空等待）
 * 2. 等待 [delayMs] 后进入下一步
 * 3. 整个步骤按 [repeat] 次重复
 *
 * @property keyCode KEY_DOWN / KEY_UP 使用，指向另一个已绑定按键
 * @property x       起点 X（绝对像素）
 * @property y       起点 Y（绝对像素）
 * @property toX     SWIPE 终点 X
 * @property toY     SWIPE 终点 Y
 * @property holdMs  按住 / 长按时长
 * @property delayMs 与下一步的间隔，默认一个时间粒度
 * @property repeat  重复次数，最小 1
 */
@Serializable
data class MacroStep(
    val action: StepAction = StepAction.WAIT,
    val keyCode: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val toX: Int? = null,
    val toY: Int? = null,
    val holdMs: Long = 0L,
    val delayMs: Long = Constants.MACRO_GRANULARITY_MS,
    val repeat: Int = 1,
) {

    /** 归一化后的重复次数，至少执行 1 次。 */
    val safeRepeat: Int get() = if (repeat < 1) 1 else repeat

    /** 归一化后的步骤间延时，不小于 0。 */
    val safeDelayMs: Long get() = if (delayMs < 0L) 0L else delayMs

    /** 归一化后的按住时长，不小于 0。 */
    val safeHoldMs: Long get() = if (holdMs < 0L) 0L else holdMs

    /** 本步骤（含重复）占用的总时长，供 UI 预估宏耗时。 */
    fun totalDurationMs(): Long = (safeHoldMs + safeDelayMs) * safeRepeat

    /** 按比例缩放坐标（P1-4）。 */
    fun scaled(scaleX: Double, scaleY: Double): MacroStep = copy(
        x = x?.let { Math.round(it * scaleX).toInt() },
        y = y?.let { Math.round(it * scaleY).toInt() },
        toX = toX?.let { Math.round(it * scaleX).toInt() },
        toY = toY?.let { Math.round(it * scaleY).toInt() },
    )

    companion object {

        /** 等待步骤。 */
        fun wait(delayMs: Long): MacroStep =
            MacroStep(action = StepAction.WAIT, delayMs = delayMs)

        /** 点击步骤。 */
        fun click(x: Int, y: Int, delayMs: Long = Constants.MACRO_GRANULARITY_MS): MacroStep =
            MacroStep(action = StepAction.CLICK, x = x, y = y, delayMs = delayMs)

        /** 滑动步骤。 */
        fun swipe(
            x: Int,
            y: Int,
            toX: Int,
            toY: Int,
            holdMs: Long = Constants.DEFAULT_SWIPE_DURATION_MS,
            delayMs: Long = Constants.MACRO_GRANULARITY_MS,
        ): MacroStep = MacroStep(
            action = StepAction.SWIPE,
            x = x,
            y = y,
            toX = toX,
            toY = toY,
            holdMs = holdMs,
            delayMs = delayMs,
        )

        /** 按住某个已绑定按键对应的坐标。 */
        fun keyDown(
            keyCode: Int,
            holdMs: Long,
            delayMs: Long = Constants.MACRO_GRANULARITY_MS,
        ): MacroStep = MacroStep(
            action = StepAction.KEY_DOWN,
            keyCode = keyCode,
            holdMs = holdMs,
            delayMs = delayMs,
        )

        /** 松开标记步骤。 */
        fun keyUp(keyCode: Int, delayMs: Long = Constants.MACRO_GRANULARITY_MS): MacroStep =
            MacroStep(action = StepAction.KEY_UP, keyCode = keyCode, delayMs = delayMs)
    }
}
