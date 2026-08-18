package com.keymapper.ui.debug

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.keymapper.util.Constants

/**
 * 测试模式按键高亮层（P1-5）。
 *
 * 每次收到触发事件就在对应坐标画一圈扩散的高亮环 + 按键文案，
 * [Constants.HIGHLIGHT_DURATION_MS] 后自动消失。
 *
 * 刷新节奏可通过 [frameIntervalMs] 调整：正常 16ms（约 60fps），
 * 低电量时由宿主服务改为 [Constants.LOW_BATTERY_HIGHLIGHT_FRAME_MS]（P2-3 降频，
 * 只影响动画平滑度，不影响按键触发精度）。
 */
class DebugHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 动画帧间隔（毫秒）。 */
    var frameIntervalMs: Long = Constants.NORMAL_HIGHLIGHT_FRAME_MS
        set(value) {
            field = value.coerceIn(8L, 500L)
        }

    private data class Highlight(
        val x: Float,
        val y: Float,
        val color: Int,
        val text: String,
        val startedAt: Long,
    )

    private val highlights = ArrayList<Highlight>()

    private val density = resources.displayMetrics.density
    private val minRadius = 16f * density
    private val maxRadius = 46f * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var invalidateScheduled = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            invalidateScheduled = false
            invalidate()
        }
    }

    /**
     * 触发一次高亮。
     *
     * @param x 屏幕绝对 X（已缩放）
     * @param y 屏幕绝对 Y（已缩放）
     * @param colorHex 标签颜色 hex 字符串
     * @param text 显示文案（按键名）
     */
    fun highlight(x: Int, y: Int, colorHex: String, text: String) {
        val color = try {
            Color.parseColor(colorHex)
        } catch (e: IllegalArgumentException) {
            Color.parseColor(Constants.DEFAULT_LABEL_COLOR)
        }
        highlights.add(
            Highlight(
                x = x.toFloat(),
                y = y.toFloat(),
                color = color,
                text = text,
                startedAt = SystemClock.uptimeMillis(),
            ),
        )
        scheduleFrame()
    }

    /** 立即清空全部高亮（退出测试模式）。 */
    fun clear() {
        highlights.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (highlights.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        val iterator = highlights.iterator()
        var hasAlive = false

        while (iterator.hasNext()) {
            val item = iterator.next()
            val elapsed = now - item.startedAt
            if (elapsed >= Constants.HIGHLIGHT_DURATION_MS) {
                iterator.remove()
                continue
            }
            hasAlive = true

            val progress = elapsed.toFloat() / Constants.HIGHLIGHT_DURATION_MS.toFloat()
            val radius = minRadius + (maxRadius - minRadius) * progress
            val alpha = (255 * (1f - progress)).toInt().coerceIn(0, 255)

            fillPaint.color = withAlpha(item.color, (alpha * 0.35f).toInt())
            canvas.drawCircle(item.x, item.y, radius, fillPaint)

            ringPaint.color = withAlpha(item.color, alpha)
            canvas.drawCircle(item.x, item.y, radius, ringPaint)

            textPaint.alpha = alpha
            canvas.drawText(item.text, item.x, item.y - radius - 6f * density, textPaint)
        }

        if (hasAlive) {
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        if (invalidateScheduled) return
        invalidateScheduled = true
        postDelayed(frameRunnable, frameIntervalMs)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameRunnable)
        invalidateScheduled = false
        super.onDetachedFromWindow()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}
