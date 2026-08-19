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

    /** 拖动微调完成回调：坐标已按网格吸附处理（管理态下拖动生效）。 */
    var onMarkerDrag: ((binding: KeyBinding, x: Int, y: Int) -> Unit)? = null

    /** 管理态开关。 */
    var manageMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 是否开启网格吸附（拖动时坐标对齐网格，避免键位重叠冲突）。 */
    var snapToGrid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 网格间距（dp），<=0 时使用默认值。 */
    var gridSize: Int = 0

    private var markers: List<KeyBinding> = emptyList()

    private val density = resources.displayMetrics.density

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
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#26FFFFFF")
    }

    private val labelRect = RectF()

    /** 视图在屏幕上的位置；绘制时把绝对屏幕坐标转回局部坐标。 */
    private val locationOnScreen = IntArray(2)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var pendingBinding: KeyBinding? = null

    /** 正在拖动微调的标记与当前拖到的位置（屏幕绝对像素，已吸附）。 */
    private var draggingBinding: KeyBinding? = null
    private var dragX = 0
    private var dragY = 0

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

        // 管理态 + 网格吸附：先铺一层浅网格辅助对齐
        if (manageMode && snapToGrid) {
            drawGrid(canvas)
        }

        for (binding in markers) {
            val color = parseColor(binding.labelColor)
            // 拖动中的标记画在手指当前位置（已吸附），其余画在绑定坐标
            val drawn = if (binding === draggingBinding) binding.copy(x = dragX, y = dragY) else binding
            val cx = drawn.x.toFloat() - offsetX
            val cy = drawn.y.toFloat() - offsetY
            val radius = markerRadiusOf(binding)

            // 圆点 + 白色描边，保证在任何背景上都可见
            dotPaint.color = color
            canvas.drawCircle(cx, cy, radius, dotPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)

            // 管理态下额外画出可点击范围
            if (manageMode) {
                canvas.drawCircle(cx, cy, hitRadiusOf(binding), managePaint)
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
                val moved = abs(event.rawX - downX) > touchSlop ||
                    abs(event.rawY - downY) > touchSlop
                // 超出触摸阈值：由「等待长按」切换为「拖动微调」
                if (moved && draggingBinding == null && pendingBinding != null) {
                    removeCallbacks(longPressRunnable)
                    draggingBinding = pendingBinding
                    pendingBinding = null
                }
                if (draggingBinding != null) {
                    dragX = snapValue(event.rawX)
                    dragY = snapValue(event.rawY)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val dragged = draggingBinding
                if (dragged != null) {
                    draggingBinding = null
                    onMarkerDrag?.invoke(dragged, dragX, dragY)
                }
                pendingBinding = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pendingBinding = null
                draggingBinding = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /** 命中测试：返回距离触点最近且在命中半径内的标记（大标记有更大的命中范围）。 */
    private fun findMarkerAt(x: Float, y: Float): KeyBinding? =
        markers.filter { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= hitRadiusOf(it) }
            .minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }

    /** 标记的实际绘制半径（px）：绑定自定义半径，未设置时用默认值。 */
    private fun markerRadiusOf(binding: KeyBinding): Float =
        binding.effectiveRadiusDp() * density

    /** 标记的触摸命中半径（px）：不小于绘制半径，方便拖动大标记。 */
    private fun hitRadiusOf(binding: KeyBinding): Float =
        maxOf(hitRadius, markerRadiusOf(binding))

    /** 网格间距（px）。 */
    private fun effectiveGridPx(): Float =
        (if (gridSize > 0) gridSize else Constants.DEFAULT_GRID_SIZE_DP) * density

    /** 屏幕绝对坐标 → 网格吸附后的整数坐标。 */
    private fun snapValue(v: Float): Int {
        if (!snapToGrid) return v.toInt()
        val step = effectiveGridPx()
        if (step <= 0f) return v.toInt()
        return (Math.round(v / step) * step).toInt()
    }

    /** 铺一层浅网格（管理态 + 吸附开启时）。 */
    private fun drawGrid(canvas: Canvas) {
        val step = effectiveGridPx()
        if (step <= 0f) return
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y <= height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
    }

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
