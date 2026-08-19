package com.keymapper.ui.macro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.keymapper.domain.model.MacroStep
import com.keymapper.domain.model.StepAction
import com.keymapper.util.Constants
import kotlin.math.abs

/**
 * 宏编辑采点视图（功能3：一次性序列，如换装备）。
 *
 * 交互：
 * - **点击**屏幕某处 → 添加一个「点击」步骤（坐标为屏幕绝对像素）
 * - **按住拖动** → 添加一个「滑动」步骤（起点 → 终点）
 *
 * 与 [com.keymapper.ui.mapping.CrosshairView] 一样，对外使用屏幕绝对坐标，
 * 内部绘制时通过 [locationOnScreen] 换算回 View 局部坐标。
 */
class MacroEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 新增一个步骤（点击或滑动）。 */
    var onStepAdded: ((MacroStep) -> Unit)? = null

    private var steps: List<MacroStep> = emptyList()

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val dotRadius = 10f * density

    private val scrimColor = 0x40000000.toInt()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xCC00E5FF.toInt()
    }
    private val rubberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xAAFFFFFF.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val locationOnScreen = IntArray(2)

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f

    init {
        isClickable = true
        setBackgroundColor(scrimColor)
    }

    /** 设置现有步骤（坐标须为屏幕绝对像素）。 */
    fun setSteps(list: List<MacroStep>) {
        steps = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(locationOnScreen)
        val ox = locationOnScreen[0].toFloat()
        val oy = locationOnScreen[1].toFloat()

        // 已添加的步骤：点击画圆点，滑动画带箭头的连线
        steps.forEachIndexed { index, step ->
            when (step.action) {
                StepAction.CLICK -> {
                    val x = (step.x ?: 0).toFloat() - ox
                    val y = (step.y ?: 0).toFloat() - oy
                    drawDot(canvas, index + 1, x, y, accentColor(index))
                }

                StepAction.SWIPE -> {
                    val x0 = (step.x ?: 0).toFloat() - ox
                    val y0 = (step.y ?: 0).toFloat() - oy
                    val x1 = (step.toX ?: 0).toFloat() - ox
                    val y1 = (step.toY ?: 0).toFloat() - oy
                    linePaint.color = accentColor(index)
                    canvas.drawLine(x0, y0, x1, y1, linePaint)
                    drawDot(canvas, index + 1, x0, y0, accentColor(index))
                    drawDot(canvas, index + 1, x1, y1, accentColor(index))
                }

                else -> Unit
            }
        }

        // 拖动中的橡皮筋预览
        if (dragging) {
            canvas.drawLine(
                downX - ox, downY - oy,
                dragX - ox, dragY - oy,
                rubberPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    dragX = event.rawX
                    dragY = event.rawY
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val step = MacroStep.swipe(
                        x = downX.toInt(),
                        y = downY.toInt(),
                        toX = event.rawX.toInt(),
                        toY = event.rawY.toInt(),
                    )
                    onStepAdded?.invoke(step)
                } else {
                    val step = MacroStep.click(
                        x = downX.toInt(),
                        y = downY.toInt(),
                        delayMs = DEFAULT_STEP_DELAY_MS,
                    )
                    onStepAdded?.invoke(step)
                }
                dragging = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        dragging = false
        super.onDetachedFromWindow()
    }

    /** 画一个带序号的圆点。 */
    private fun drawDot(canvas: Canvas, number: Int, x: Float, y: Float, color: Int) {
        dotPaint.color = color
        canvas.drawCircle(x, y, dotRadius, dotPaint)
        canvas.drawCircle(x, y, dotRadius, ringPaint)
        val text = number.toString()
        val textWidth = textPaint.measureText(text)
        canvas.drawText(text, x - textWidth / 2f, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    /** 按步骤序号取色板颜色，保证相邻步骤可区分。 */
    private fun accentColor(index: Int): Int {
        val palette = Constants.LABEL_COLOR_PALETTE
        if (palette.isEmpty()) return Color.parseColor(Constants.DEFAULT_LABEL_COLOR)
        return try {
            Color.parseColor(palette[index % palette.size])
        } catch (e: IllegalArgumentException) {
            Color.parseColor(Constants.DEFAULT_LABEL_COLOR)
        }
    }

    private companion object {
        /** 新步骤的默认间隔：200ms，适合换装备等需要稳定节奏的一次性序列。 */
        const val DEFAULT_STEP_DELAY_MS = 200L
    }
}
