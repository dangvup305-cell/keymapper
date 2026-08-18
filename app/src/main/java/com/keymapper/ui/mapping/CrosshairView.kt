package com.keymapper.ui.mapping

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.keymapper.R
import com.keymapper.domain.model.ActionType
import com.keymapper.domain.model.KeyBinding
import com.keymapper.util.Constants
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 映射模式采点视图（P0-2 / P0-4 / Q4）。
 *
 * 绘制内容：
 * 1. 半透明遮罩（让用户明确「当前处于映射模式」，同时仍能看清下层界面）；
 * 2. 已完成的映射标记（圆点 + 按键标签 + 手势类型后缀）；
 * 3. 待绑定点的十字准星（横竖贯穿线 + 中心点）。
 *
 * 坐标约定（Q4 决议）：
 * - 对外回调 [onPointPicked] 与 [setBindings] 使用的都是**屏幕绝对像素**（`MotionEvent.rawX/rawY`）；
 * - 内部绘制时通过 [locationOnScreen] 把绝对坐标换算为 View 局部坐标，
 *   因此即使本视图没有铺满整个屏幕（状态栏 / 刘海裁剪）也不会画偏。
 */
class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 采到一个新坐标（屏幕绝对像素）。 */
    var onPointPicked: ((x: Int, y: Int) -> Unit)? = null

    /** 长按已有标记（用于删除/重绑/改色/改触发类型）。 */
    var onMarkerLongPress: ((KeyBinding) -> Unit)? = null

    /** 锁定态：等待用户按键时不再接受新的采点，避免误触改坐标。 */
    var locked: Boolean = false

    private var bindings: List<KeyBinding> = emptyList()

    /** 待绑定点（屏幕绝对像素），-1 表示无。 */
    private var pendingX: Int = NO_POINT
    private var pendingY: Int = NO_POINT

    private val density = resources.displayMetrics.density

    private val dotRadius = 9f * density
    private val hitRadius = 28f * density
    private val centerRadius = 5f * density
    private val labelPadH = 6f * density
    private val labelPadV = 3f * density
    private val labelOffset = 16f * density
    private val labelCorner = 4f * density

    private val scrimColor = resolveColor(R.color.overlay_scrim, DEFAULT_SCRIM)
    private val lineColor = resolveColor(R.color.crosshair_line, DEFAULT_LINE)
    private val centerColor = resolveColor(R.color.crosshair_center, DEFAULT_CENTER)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = lineColor
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = centerColor
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelRect = RectF()
    private val locationOnScreen = IntArray(2)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressFired = false
    private var pressedMarker: KeyBinding? = null

    private val longPressRunnable = Runnable {
        val marker = pressedMarker
        if (marker != null) {
            longPressFired = true
            onMarkerLongPress?.invoke(marker)
        }
    }

    init {
        isClickable = true
        setBackgroundColor(scrimColor)
    }

    // ------------------------------------------------------------------
    // 数据入口
    // ------------------------------------------------------------------

    /** 设置已完成的映射标记（坐标为屏幕绝对像素）。 */
    fun setBindings(list: List<KeyBinding>) {
        bindings = list
        invalidate()
    }

    /** 设置待绑定的坐标（屏幕绝对像素）。 */
    fun setPending(x: Int, y: Int) {
        pendingX = x
        pendingY = y
        invalidate()
    }

    /** 清除待绑定坐标。 */
    fun clearPending() {
        pendingX = NO_POINT
        pendingY = NO_POINT
        invalidate()
    }

    /** 是否存在待绑定坐标。 */
    fun hasPending(): Boolean = pendingX != NO_POINT && pendingY != NO_POINT

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(locationOnScreen)

        drawMarkers(canvas)
        drawCrosshair(canvas)
    }

    /** 画已完成的映射标记（P0-4 同款样式，保证映射态与运行态观感一致）。 */
    private fun drawMarkers(canvas: Canvas) {
        for (item in bindings) {
            val cx = toLocalX(item.x)
            val cy = toLocalY(item.y)
            val color = parseColor(item.labelColor)

            dotPaint.color = color
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            canvas.drawCircle(cx, cy, dotRadius, ringPaint)

            val text = item.effectiveLabel() + actionSuffix(item.actionType)
            val textWidth = labelTextPaint.measureText(text)
            val metrics = labelTextPaint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent

            val left = cx - textWidth / 2f - labelPadH
            val top = cy + labelOffset
            labelRect.set(
                left,
                top,
                left + textWidth + labelPadH * 2,
                top + textHeight + labelPadV * 2,
            )
            labelBgPaint.color = withAlpha(color, LABEL_BG_ALPHA)
            canvas.drawRoundRect(labelRect, labelCorner, labelCorner, labelBgPaint)
            canvas.drawText(
                text,
                labelRect.left + labelPadH,
                labelRect.top + labelPadV - metrics.ascent,
                labelTextPaint,
            )
        }
    }

    /** 画待绑定点的十字准星。 */
    private fun drawCrosshair(canvas: Canvas) {
        if (!hasPending()) return
        val cx = toLocalX(pendingX)
        val cy = toLocalY(pendingY)

        canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)
        canvas.drawLine(cx, 0f, cx, height.toFloat(), linePaint)
        canvas.drawCircle(cx, cy, centerRadius, centerPaint)
        canvas.drawCircle(cx, cy, hitRadius, linePaint)
    }

    // ------------------------------------------------------------------
    // 触摸
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                longPressFired = false
                pressedMarker = findMarkerAt(event.rawX, event.rawY)
                if (pressedMarker != null) {
                    postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downRawX) > touchSlop ||
                    abs(event.rawY - downRawY) > touchSlop
                ) {
                    removeCallbacks(longPressRunnable)
                    pressedMarker = null
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val moved = abs(event.rawX - downRawX) > touchSlop ||
                    abs(event.rawY - downRawY) > touchSlop
                if (!longPressFired && !moved && !locked) {
                    onPointPicked?.invoke(event.rawX.toInt(), event.rawY.toInt())
                }
                pressedMarker = null
                longPressFired = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pressedMarker = null
                longPressFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /** 命中测试：绝对坐标下距触点最近且在 [hitRadius] 内的标记。 */
    private fun findMarkerAt(rawX: Float, rawY: Float): KeyBinding? =
        bindings.filter { distanceTo(it, rawX, rawY) <= hitRadius }
            .minByOrNull { distanceTo(it, rawX, rawY) }

    private fun distanceTo(item: KeyBinding, rawX: Float, rawY: Float): Double =
        hypot((item.x - rawX).toDouble(), (item.y - rawY).toDouble())

    // ------------------------------------------------------------------
    // 坐标换算与工具
    // ------------------------------------------------------------------

    /** 屏幕绝对 X → View 局部 X。 */
    private fun toLocalX(absX: Int): Float = (absX - locationOnScreen[0]).toFloat()

    /** 屏幕绝对 Y → View 局部 Y。 */
    private fun toLocalY(absY: Int): Float = (absY - locationOnScreen[1]).toFloat()

    /** 手势类型后缀标识（与 [com.keymapper.ui.floating.MarkerLayerView] 保持一致）。 */
    private fun actionSuffix(type: ActionType): String = when (type) {
        ActionType.CLICK -> ""
        ActionType.LONG_PRESS -> " ⏱"
        ActionType.SWIPE -> " ↗"
        ActionType.PINCH -> " ⤡"
        ActionType.MACRO -> " ⚡"
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.parseColor(Constants.DEFAULT_LABEL_COLOR)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    /** 读取颜色资源，失败时使用代码内默认值（避免预览/异常主题下崩溃）。 */
    private fun resolveColor(resId: Int, fallback: Int): Int = try {
        @Suppress("DEPRECATION")
        resources.getColor(resId)
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        const val NO_POINT = -1
        const val LABEL_BG_ALPHA = 210
        const val DEFAULT_SCRIM = 0x66000000.toInt()
        const val DEFAULT_LINE = 0xCC00E5FF.toInt()
        const val DEFAULT_CENTER = 0xFFFF3D00.toInt()
    }
}
