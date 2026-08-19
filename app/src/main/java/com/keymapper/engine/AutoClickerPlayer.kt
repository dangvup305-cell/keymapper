package com.keymapper.engine

import android.os.Handler
import android.os.Looper
import com.keymapper.domain.model.AutoClickerConfig
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 循环连点器播放器（模式二·屏幕辅助）。
 *
 * 主线程 Handler 循环：依次对每个坐标注入一次点击 → 等待 [AutoClickerConfig.intervalMs] → 再循环。
 * 停止条件：达到次数 / 达到时长 / 显式 [stop] / 无障碍服务不可用（注入通道断开自动停）。
 *
 * 与按键映射引擎相互独立：连点运行期间用户仍可正常触屏操作（离散点击在不同位置通常互不干扰）。
 * 全程只做 click 注入，不占常驻资源（无协程、无轮询，空闲时无任何定时器）。
 */
class AutoClickerPlayer {

    private val handler = Handler(Looper.getMainLooper())

    /** 手势注入通道，由无障碍服务 attach/detach。 */
    private var injector: GestureInjector? = null

    @Volatile
    private var running = false

    private var tick: Runnable? = null
    private var startTime = 0L
    private var firedCount = 0
    private var config: AutoClickerConfig = AutoClickerConfig()

    /** 运行状态监听（悬浮层「停止连点」按钮、连点器页面共用）。 */
    private val stateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** 当前是否在连点。 */
    val isRunning: Boolean get() = running

    /** 绑定注入通道（无障碍服务连接时调用）。 */
    fun attach(injector: GestureInjector) {
        this.injector = injector
    }

    /** 解绑并停止（无障碍服务断开时调用）。 */
    fun detach() {
        stop()
        injector = null
    }

    /** 注册运行状态监听。 */
    fun addStateListener(listener: (Boolean) -> Unit) {
        stateListeners.addIfAbsent(listener)
    }

    /** 注销运行状态监听。 */
    fun removeStateListener(listener: (Boolean) -> Unit) {
        stateListeners.remove(listener)
    }

    /** 开始连点；已在运行则先停止再按新配置重启。 */
    fun start(cfg: AutoClickerConfig) {
        if (!cfg.hasPoints) return
        stop()
        config = cfg
        running = true
        startTime = System.currentTimeMillis()
        firedCount = 0

        val interval = cfg.safeInterval()
        val loop = object : Runnable {
            override fun run() {
                if (!running) return

                // 达到次数 / 时长即自动停
                if (config.runCount > 0 && firedCount >= config.runCount) {
                    stop()
                    return
                }
                if (config.durationMs > 0 && System.currentTimeMillis() - startTime >= config.durationMs) {
                    stop()
                    return
                }

                val inj = injector
                if (inj == null || !inj.isInjectorReady()) {
                    stop()
                    return
                }
                for (point in config.points) {
                    inj.injectClick(point.x, point.y)
                }
                firedCount += config.points.size
                handler.postDelayed(this, interval)
            }
        }
        tick = loop
        handler.post(loop)
        notifyState(true)
    }

    /** 停止连点（幂等）。 */
    fun stop() {
        if (!running && tick == null) return
        running = false
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        notifyState(false)
    }

    private fun notifyState(runningState: Boolean) {
        for (listener in stateListeners) {
            try {
                listener(runningState)
            } catch (ignored: Exception) {
                // 单个监听异常不影响其它监听
            }
        }
    }
}
