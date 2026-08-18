package com.keymapper.engine

import com.keymapper.util.Constants

/**
 * 手势注入抽象。
 *
 * [com.keymapper.accessibility.KeyMapperAccessibilityService] 是唯一的真实实现
 * （内部走 `dispatchGesture(GestureDescription)`）。
 *
 * 引入该接口的目的：
 * 1. 让 [MappingEngine] / [MacroPlayer] 不直接依赖 `AccessibilityService`，
 *    可在纯 JVM 单元测试中用假实现验证匹配与时序逻辑；
 * 2. 明确划定「引擎 → 系统服务」的唯一通道，避免引擎里散落 Android API 调用。
 *
 * 所有坐标均为**当前屏幕**的绝对像素（已由 [com.keymapper.util.ResolutionScaler] 换算完毕）。
 */
interface GestureInjector {

    /** 服务当前是否可用（无障碍未连接时返回 false）。 */
    fun isInjectorReady(): Boolean

    /** 注入一次点击。 */
    fun injectClick(
        x: Int,
        y: Int,
        durationMs: Long = Constants.DEFAULT_CLICK_DURATION_MS,
    ): Boolean

    /** 注入一次长按。 */
    fun injectLongPress(
        x: Int,
        y: Int,
        durationMs: Long = Constants.DEFAULT_LONG_PRESS_MS,
    ): Boolean

    /** 注入一次滑动。 */
    fun injectSwipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long = Constants.DEFAULT_SWIPE_DURATION_MS,
    ): Boolean

    /**
     * 注入一次双指缩放。
     *
     * @param focalX 缩放焦点 X
     * @param focalY 缩放焦点 Y
     * @param scaleFactor >1 放大（两指外扩）、<1 缩小（两指内收）
     * @param radius 起始两指间距的一半
     */
    fun injectPinch(
        focalX: Int,
        focalY: Int,
        scaleFactor: Float,
        durationMs: Long = Constants.DEFAULT_PINCH_DURATION_MS,
        radius: Int = 240,
    ): Boolean
}
