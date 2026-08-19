package com.keymapper.ui.clicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.keymapper.domain.model.Point
import com.keymapper.util.Constants

/**
 * 连点器选点视图（模式二·屏幕辅助）。
 *
 * 点击屏幕添加一个连点坐标（屏幕绝对像素），已添加的点画为带序号的圆点。
 * 与 [com.keymapper.ui.mapping.CrosshairView] 一样，对外用屏幕绝对坐标，
 * 内部绘制时经 [locationOnScreen] 换算回局部坐标。
 */
class PointPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 新点回调（屏幕绝对像素）。 */
    var onPointAdded: ((x: Int, y: Int) -> Unit)? = null

    private var points: List<Point> = emptyList()

    private val density = resources.displayMetrics.density

    private val dotRadius = 11f * density
    private val scrimColor = 0x40000000.toInt()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val locationOnScreen = IntArray(2)

    init {
        isClickable = true
        setBackgroundColor(scrimColor)
    }

    /** 设置已选点位（屏幕绝对像素）。 */
    fun setPoints(list: List<Point>) {
        points = list
        invalidate()
    }

    /** 当前已选点位。 */
    fun points(): List<Point> = points

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(locationOnScreen)
        val ox = locationOnScreen[0].toFloat()
        val oy = locationOnScreen[1].toFloat()

        points.forEachIndexed { index, point ->
            val cx = point.x.toFloat() - ox
            val cy = point.y.toFloat() - oy
            val color = accentColor(index)
            dotPaint.color = color
            canvas.drawCircle(cx, cy, dotRadius, dotPaint)
            canvas.drawCircle(cx, cy, dotRadius, ringPaint)
            val text = (index + 1).toString()
            val textWidth = textPaint.measureText(text)
            canvas.drawText(
                text,
                cx - textWidth / 2f,
                cy - (textPaint.ascent() + textPaint.descent()) / 2f,
                textPaint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onPointAdded?.invoke(event.rawX.toInt(), event.rawY.toInt())
            return true
        }
        return true
    }

    /** 按序号取色板颜色，保证相邻点可区分。 */
    private fun accentColor(index: Int): Int {
        val palette = Constants.LABEL_COLOR_PALETTE
        return try {
            Color.parseColor(palette[index % palette.size])
        } catch (e: Exception) {
            Color.parseColor(Constants.DEFAULT_LABEL_COLOR)
        }
    }
}
