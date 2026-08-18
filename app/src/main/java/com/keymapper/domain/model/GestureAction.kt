package com.keymapper.domain.model

import com.keymapper.util.Constants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 屏幕坐标点（绝对像素 px）。
 */
@Serializable
data class Point(
    val x: Int = 0,
    val y: Int = 0,
) {
    /** 按比例缩放（P1-4）。 */
    fun scaled(scaleX: Double, scaleY: Double): Point = Point(
        x = Math.round(x * scaleX).toInt(),
        y = Math.round(y * scaleY).toInt(),
    )
}

/**
 * 手势类型（P2-1）。JSON 中使用小写字符串，保证与 [ActionType] 的取值一致。
 */
@Serializable
enum class GestureType {
    @SerialName("click")
    CLICK,

    @SerialName("longpress")
    LONG_PRESS,

    @SerialName("swipe")
    SWIPE,

    @SerialName("pinch")
    PINCH,
}

/**
 * 手势参数（P2-1）。
 *
 * 各类型使用的字段：
 * - [GestureType.CLICK]      仅用 [durationMs]（不填则用默认点击时长），坐标取 [KeyBinding.x]/[KeyBinding.y]
 * - [GestureType.LONG_PRESS] 用 [durationMs] 作为按住时长
 * - [GestureType.SWIPE]      用 [from]（缺省时取绑定坐标）与 [to]，[durationMs] 为滑动总时长
 * - [GestureType.PINCH]      用 [focal]（缺省时取绑定坐标）、[scaleFactor]（>1 放大、<1 缩小）与 [durationMs]
 *
 * 扩展新手势只需在此追加字段，旧配置反序列化时按默认值填充，保持 JSON 向前兼容。
 */
@Serializable
data class GestureAction(
    val type: GestureType = GestureType.CLICK,
    val durationMs: Long = 0L,
    val from: Point? = null,
    val to: Point? = null,
    val scaleFactor: Float = 1.0f,
    val focal: Point? = null,
    /** 双指缩放的初始半径（px），决定两指起始间距 */
    val pinchRadius: Int = 240,
) {

    /**
     * 返回本手势的有效时长：未显式指定时按类型给默认值。
     */
    fun effectiveDurationMs(): Long {
        if (durationMs > 0L) return durationMs.coerceAtMost(Constants.MAX_GESTURE_DURATION_MS)
        return when (type) {
            GestureType.CLICK -> Constants.DEFAULT_CLICK_DURATION_MS
            GestureType.LONG_PRESS -> Constants.DEFAULT_LONG_PRESS_MS
            GestureType.SWIPE -> Constants.DEFAULT_SWIPE_DURATION_MS
            GestureType.PINCH -> Constants.DEFAULT_PINCH_DURATION_MS
        }
    }

    /**
     * 按比例缩放内部坐标（P1-4）。半径按 X/Y 平均比例缩放。
     */
    fun scaled(scaleX: Double, scaleY: Double): GestureAction = copy(
        from = from?.scaled(scaleX, scaleY),
        to = to?.scaled(scaleX, scaleY),
        focal = focal?.scaled(scaleX, scaleY),
        pinchRadius = Math.round(pinchRadius * (scaleX + scaleY) / 2.0).toInt(),
    )

    companion object {

        /** 构造一个长按手势。 */
        fun longPress(durationMs: Long = Constants.DEFAULT_LONG_PRESS_MS): GestureAction =
            GestureAction(type = GestureType.LONG_PRESS, durationMs = durationMs)

        /** 构造一个滑动手势。 */
        fun swipe(
            from: Point,
            to: Point,
            durationMs: Long = Constants.DEFAULT_SWIPE_DURATION_MS,
        ): GestureAction = GestureAction(
            type = GestureType.SWIPE,
            durationMs = durationMs,
            from = from,
            to = to,
        )

        /** 构造一个双指缩放手势。 */
        fun pinch(
            focal: Point,
            scaleFactor: Float,
            durationMs: Long = Constants.DEFAULT_PINCH_DURATION_MS,
            radius: Int = 240,
        ): GestureAction = GestureAction(
            type = GestureType.PINCH,
            durationMs = durationMs,
            scaleFactor = scaleFactor,
            focal = focal,
            pinchRadius = radius,
        )
    }
}

/**
 * [ActionType] → [GestureType] 的映射；[ActionType.MACRO] 不是手势，返回 null。
 */
fun ActionType.toGestureType(): GestureType? = when (this) {
    ActionType.CLICK -> GestureType.CLICK
    ActionType.LONG_PRESS -> GestureType.LONG_PRESS
    ActionType.SWIPE -> GestureType.SWIPE
    ActionType.PINCH -> GestureType.PINCH
    ActionType.MACRO -> null
}
