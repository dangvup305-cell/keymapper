package com.keymapper.ui.floating

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.keymapper.databinding.FloatingButtonBinding
import kotlin.math.abs

/**
 * 可拖拽悬浮小球（P0-1）。
 *
 * 交互：
 * - 按下并移动超过 touchSlop → 判定为拖拽，通过 [onDragDelta] 把位移交给
 *   [com.keymapper.service.FloatingWindowService] 更新 `WindowManager.LayoutParams`
 * - 按下抬起且未超过 slop / 未超过长按时长 → 判定为单击，回调 [onSingleTap]（展开面板）
 * - 长按 → 回调 [onLongPress]（预留：快速隐藏/切换）
 *
 * 视图自身不持有 WindowManager，位置更新完全交给宿主服务，职责单一。
 */
class FloatingButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: FloatingButtonBinding =
        FloatingButtonBinding.inflate(LayoutInflater.from(context), this)

    /** 单击回调（展开操作面板）。 */
    var onSingleTap: (() -> Unit)? = null

    /** 长按回调。 */
    var onLongPress: (() -> Unit)? = null

    /** 拖拽位移回调：参数为相对上一次事件的位移（px）。 */
    var onDragDelta: ((dx: Int, dy: Int) -> Unit)? = null

    /** 拖拽结束回调（可用于吸边）。 */
    var onDragEnd: (() -> Unit)? = null

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout: Long = ViewConfiguration.getLongPressTimeout().toLong()

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var longPressFired = false
    private var downTime = 0L

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            onLongPress?.invoke()
        }
    }

    init {
        // 让球体可点击，同时不拦截子 View 的绘制
        isClickable = true
        binding.ivBall.visibility = View.VISIBLE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                longPressFired = false
                downTime = System.currentTimeMillis()
                postDelayed(longPressRunnable, longPressTimeout)
                alpha = PRESSED_ALPHA
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                if (!dragging && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    if (dx != 0 || dy != 0) {
                        onDragDelta?.invoke(dx, dy)
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                alpha = NORMAL_ALPHA
                if (dragging) {
                    onDragEnd?.invoke()
                } else if (!longPressFired) {
                    onSingleTap?.invoke()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                alpha = NORMAL_ALPHA
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /** 设置小球透明度（P2-3 低电量时可降低视觉开销）。 */
    fun setIdleAlpha(value: Float) {
        alpha = value.coerceIn(0.2f, 1.0f)
    }

    private companion object {
        const val NORMAL_ALPHA = 1.0f
        const val PRESSED_ALPHA = 0.7f
    }
}
