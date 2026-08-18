package com.keymapper.domain.model

import com.keymapper.util.Constants
import com.keymapper.util.KeyEventUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 触发类型（P0-3 / P1-3 / P2-1）。JSON 中以小写字符串存储，缺失时默认 [CLICK]。
 */
@Serializable
enum class ActionType {
    @SerialName("click")
    CLICK,

    @SerialName("longpress")
    LONG_PRESS,

    @SerialName("swipe")
    SWIPE,

    @SerialName("pinch")
    PINCH,

    @SerialName("macro")
    MACRO,
}

/**
 * 单条按键映射（P0-3 / P0-4 / P1-2 / P1-3 / P2-1）。
 *
 * 坐标 [x]/[y] 统一为**屏幕绝对像素（px）**，其参考分辨率记录在
 * [MappingConfig.screenWidth]/[MappingConfig.screenHeight] 中。
 *
 * @property keyCode        主键键码（`android.view.KeyEvent.KEYCODE_*`），0 表示 KEYCODE_UNKNOWN
 * @property keyCombination 组合键键码列表（P1-2），如 `[KEYCODE_CTRL_LEFT, KEYCODE_1]`；为空表示单键
 * @property labelColor     标签颜色，hex 字符串，如 `#FF5722`
 * @property actionType     触发类型
 * @property gestureParams  手势参数（P2-1），点击时可为 null
 * @property macro          绑定的宏（P1-3），非 null 时优先按宏回放
 * @property label          用户自定义标签文案，为空时由 [effectiveLabel] 回退为按键名
 */
@Serializable
data class KeyBinding(
    val x: Int = 0,
    val y: Int = 0,
    val keyCode: Int = 0,
    val keyCombination: List<Int>? = null,
    val labelColor: String = Constants.DEFAULT_LABEL_COLOR,
    val actionType: ActionType = ActionType.CLICK,
    val gestureParams: GestureAction? = null,
    val macro: Macro? = null,
    val label: String? = null,
) {

    /** 是否为组合键绑定（至少两个键码）。 */
    val isCombination: Boolean
        get() = (keyCombination?.size ?: 0) >= 2

    /** 是否绑定了可回放的宏。 */
    val hasMacro: Boolean
        get() = macro != null && macro.steps.isNotEmpty()

    /**
     * 按键签名：引擎用它作为匹配表的 key。与 [KeyEventUtil.signature] 保持唯一实现。
     */
    fun signature(): List<Int> = KeyEventUtil.signature(keyCode, keyCombination)

    /**
     * 可读按键文案，如 `A`、`Ctrl+1`。
     */
    fun displayKey(): String = KeyEventUtil.displayLabel(keyCode, keyCombination)

    /**
     * 标记层展示用的文案：优先用户自定义 [label]，否则用按键文案。
     */
    fun effectiveLabel(): String = label?.takeIf { it.isNotBlank() } ?: displayKey()

    /**
     * 本绑定实际使用的手势参数：
     * - 已显式提供 [gestureParams] 时直接返回
     * - 否则按 [actionType] 生成一份默认参数（MACRO 返回 null）
     */
    fun effectiveGesture(): GestureAction? {
        gestureParams?.let { return it }
        val type = actionType.toGestureType() ?: return null
        return GestureAction(type = type)
    }

    /**
     * 校验绑定是否可用：必须有键码、坐标非负，宏类型必须带非空宏。
     */
    fun isValid(): Boolean {
        if (x < 0 || y < 0) return false
        if (keyCode == 0 && keyCombination.isNullOrEmpty()) return false
        if (actionType == ActionType.MACRO && !hasMacro) return false
        return true
    }

    /**
     * 按比例缩放坐标与手势/宏中的内部坐标（P1-4）。
     */
    fun scaled(scaleX: Double, scaleY: Double): KeyBinding = copy(
        x = Math.round(x * scaleX).toInt(),
        y = Math.round(y * scaleY).toInt(),
        gestureParams = gestureParams?.scaled(scaleX, scaleY),
        macro = macro?.scaled(scaleX, scaleY),
    )
}
