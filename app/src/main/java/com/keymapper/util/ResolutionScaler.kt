package com.keymapper.util

import android.content.Context
import android.graphics.Point as AndroidPoint
import android.os.Build
import android.view.WindowManager
import com.keymapper.domain.model.KeyBinding
import com.keymapper.domain.model.MappingConfig
import com.keymapper.domain.model.Point

/**
 * 坐标分辨率适配（P1-4）。
 *
 * 约定（Q4 决议）：
 * - 采集坐标以覆盖层 `MotionEvent` 的原始绝对坐标为准
 * - 播放以 `Display.getRealSize()` 为目标分辨率，规避导航栏/刘海造成的偏移
 *
 * 本类的换算方法全部为纯函数，可直接在 JVM 单元测试中验证；
 * 只有 [realScreenSize] 依赖 Android 运行时。
 */
class ResolutionScaler {

    /**
     * 单轴等比缩放。
     *
     * @param value 源坐标值
     * @param srcSize 源屏幕该轴尺寸
     * @param dstSize 目标屏幕该轴尺寸
     * @return 缩放后的坐标；源尺寸非法（<=0）时原样返回
     */
    fun scaleValue(value: Int, srcSize: Int, dstSize: Int): Int {
        if (srcSize <= 0 || dstSize <= 0) return value
        if (srcSize == dstSize) return value
        return Math.round(value.toDouble() * dstSize.toDouble() / srcSize.toDouble()).toInt()
    }

    /** X 轴缩放。 */
    fun scaleX(x: Int, srcWidth: Int, dstWidth: Int): Int = scaleValue(x, srcWidth, dstWidth)

    /** Y 轴缩放。 */
    fun scaleY(y: Int, srcHeight: Int, dstHeight: Int): Int = scaleValue(y, srcHeight, dstHeight)

    /**
     * 缩放一个坐标点。
     */
    fun scalePoint(
        x: Int,
        y: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): Point = Point(
        x = scaleValue(x, srcWidth, dstWidth),
        y = scaleValue(y, srcHeight, dstHeight),
    )

    /**
     * 缩放单条绑定（含手势与宏内部坐标）。
     */
    fun scaleBinding(
        binding: KeyBinding,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
    ): KeyBinding {
        if (!needsScaling(srcWidth, srcHeight, dstWidth, dstHeight)) return binding
        val scaleX = dstWidth.toDouble() / srcWidth.toDouble()
        val scaleY = dstHeight.toDouble() / srcHeight.toDouble()
        return binding.scaled(scaleX, scaleY)
    }

    /**
     * 缩放整份配置：委托 [MappingConfig.scaledCopy]，保证与模型层实现唯一。
     */
    fun scaleConfig(config: MappingConfig, dstWidth: Int, dstHeight: Int): MappingConfig =
        config.scaledCopy(dstWidth, dstHeight)

    /**
     * 是否需要缩放：源/目标分辨率均有效且不相等时才需要。
     */
    fun needsScaling(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Boolean {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) return false
        return srcWidth != dstWidth || srcHeight != dstHeight
    }

    companion object {

        /**
         * 读取当前屏幕真实分辨率（含被状态栏/导航栏遮挡的部分）。
         *
         * @return 宽高像素对；取值失败时返回 (0, 0)
         */
        @Suppress("DEPRECATION")
        fun realScreenSize(context: Context): Pair<Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return 0 to 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val size = AndroidPoint()
                wm.defaultDisplay.getRealSize(size)
                size.x to size.y
            }
        }
    }
}
