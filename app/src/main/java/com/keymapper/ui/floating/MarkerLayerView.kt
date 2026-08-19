package com.keymapper.ui.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.keymapper.domain.model.ActionType
import com.keymapper.domain.model.KeyBinding
import com.keymapper.util.Constants
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 跨应用映射标记层（P0-4）。
 *
 * 挂在 `TYPE_APPLICATION_OVERLAY` 窗口上，在已绑定坐标处画一个圆点 + 按键标签。
 * 传入的坐标必须是**已按当前分辨率缩放**后的绝对像素（由
 * [com.keymapper.engine.MappingEngine.scaledBindings] 提供）。
 *
 * 两种模式：
 * - 展示态（[manageMode] = false）：宿主服务会给窗口加 `FLAG_NOT_TOUCHABLE`，完全不影响下层应用操作
 * - 管理态（[manageMode] = true）：可长按标记，回调 [onMarkerLongPress] 弹出删除/重绑菜单
 */
class MarkerLayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 长按标记回调（管理态下有效）。 */
    var onMarkerLongPress: ((KeyBinding) -> Unit)? = null

    /** 管理态开关。 */
    var manageMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var markers: List<KeyBinding> = emptyList()

    private val density = resources.displayMetrics.density

    private val dotRadius = 10f * density
    private val hitRadius = 28f * density
    private val labelPadH = 6f * density
    private val labelPadV = 3f * density
    private val labelOffset = 16f * density
    private val labelCorner = 4f * density

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
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val managePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#66FFFFFF")
    }

    private val labelRect = RectF()

    /** 视图在屏幕上的位置；绘制时把绝对屏幕坐标转回局部坐标。 */
    private val locationOnScreen = IntArray(2)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var pendingBinding: KeyBinding? = null

    private val longPressRunnable = Runnable {
        pendingBinding?.let { onMarkerLongPress?.invoke(it) }
        pendingBinding = null
    }

    /** 更新标记数据（坐标须为当前屏幕像素）。 */
    fun setMarkers(list: List<KeyBinding>) {
        markers = list
        invalidate()
    }

    /** 当前标记数据。 */
    fun markers(): List<KeyBinding> = markers

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (markers.isEmpty()) return

        getLocationOnScreen(locationOnScreen)
        val offsetX = locationOnScreen[0].toFloat()
        val offsetY = locationOnScreen[1].toFloat()

        for (binding in markers) {
            val color = parseColor(binding.labelColor)
            val cx = binding.x.toFloat() - offsetX
            val cy = binding.y.toFloat() - offsetY

            // 圆点 + 白色描边，保证在任何背景上都可见
            dotPaint.color = color
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            canvas.drawCircle(cx, cy, dotRadius, ringPaint)

            // 管理态下额外画出可点击范围
            if (manageMode) {
                canvas.drawCircle(cx, cy, hitRadius, managePaint)
            }

            // 标签：按键名 + 手势类型标识（P2-1 不同手势可区分）
            val text = binding.effectiveLabel() + actionSuffix(binding.actionType)
            val textWidth = labelTextPaint.measureText(text)
            val fontMetrics = labelTextPaint.fontMetrics
            val textHeight = fontMetrics.descent - fontMetrics.ascent

            val left = cx - textWidth / 2f - labelPadH
            val top = cy + labelOffset
            labelRect.set(left, top, left + textWidth + labelPadH * 2, top + textHeight + labelPadV * 2)

            labelBgPaint.color = withAlpha(color, LABEL_BG_ALPHA)
            canvas.drawRoundRect(labelRect, labelCorner, labelCorner, labelBgPaint)
            canvas.drawText(
                text,
                labelRect.left + labelPadH,
                labelRect.top + labelPadV - fontMetrics.ascent,
                labelTextPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!manageMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                pendingBinding = findMarkerAt(event.rawX, event.rawY)
                if (pendingBinding == null) return false
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                    removeCallbacks(longPressRunnable)
                    pendingBinding = null
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pendingBinding = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /** 命中测试：返回距离触点最近且在 [hitRadius] 内的标记。 */
    private fun findMarkerAt(x: Float, y: Float): KeyBinding? =
        markers.filter { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= hitRadius }
            .minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }

    /** 手势类型后缀标识（P2-1）。 */
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

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val LABEL_BG_ALPHA = 210
    }
}
