package com.keymapper.ui.clicker

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keymapper.R
import com.keymapper.data.ConfigRepository
import com.keymapper.databinding.ActivityAutoClickerBinding
import com.keymapper.domain.model.AutoClickerConfig
import com.keymapper.domain.model.Point
import com.keymapper.engine.AutoClickerPlayer
import org.koin.android.ext.android.inject

/**
 * 循环连点器（模式二·屏幕辅助）。
 *
 * 流程：点击屏幕选一个或多个连点坐标 → 设置间隔 / 次数 / 时长 → 开始 →
 * 自动关闭本页在后台连点；运行期间悬浮层显示「■ 停止连点」小按钮可随时停止，
 * 也可再次打开本页点「停止」。次数/时长到达自动停。
 *
 * 与按键映射完全独立：连点运行期间可正常打游戏（不同位置的离散点击互不干扰）。
 */
class AutoClickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutoClickerBinding

    private val player: AutoClickerPlayer by inject()
    private val repository: ConfigRepository by inject()

    private val points = mutableListOf<Point>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏透明覆盖层，保证 rawX/rawY 与局部坐标一致
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityAutoClickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 载入上次保存的连点配置
        val saved = repository.loadAutoClickerConfig()
        points.addAll(saved.points)
        if (saved.intervalMs > 0) binding.etInterval.setText(saved.intervalMs.toString())
        if (saved.runCount > 0) binding.etCount.setText(saved.runCount.toString())
        if (saved.durationMs > 0) binding.etDuration.setText((saved.durationMs / 1000L).toString())

        binding.pickerView.onPointAdded = { x, y ->
            points.add(Point(x, y))
            render()
        }
        binding.pickerView.setPoints(points)

        binding.btnClear.setOnClickListener {
            points.clear()
            render()
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnStart.setOnClickListener { onStartOrStop() }

        player.addStateListener(clickerStateListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        // 离开本页不停止连点（后台继续跑，由悬浮停止按钮/次数/时长控制）
        player.removeStateListener(clickerStateListener)
        super.onDestroy()
    }

    /** 连点器运行状态监听：刷新开始/停止按钮与状态文案。 */
    private val clickerStateListener: (Boolean) -> Unit = { running ->
        runOnUiThread { renderRunningState(running) }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private fun onStartOrStop() {
        if (player.isRunning) {
            player.stop()
            toast(getString(R.string.clicker_toast_stopped))
            return
        }
        if (points.isEmpty()) {
            toast(getString(R.string.clicker_toast_no_points))
            return
        }
        val interval = binding.etInterval.text?.toString()?.toLongOrNull() ?: 300L
        val count = binding.etCount.text?.toString()?.toIntOrNull() ?: 0
        val durationSec = binding.etDuration.text?.toString()?.toLongOrNull() ?: 0L
        val cfg = AutoClickerConfig(
            points = points.toList(),
            intervalMs = interval.coerceAtLeast(50L),
            runCount = count.coerceAtLeast(0),
            durationMs = (durationSec.coerceAtLeast(0L)) * 1000L,
        )
        repository.saveAutoClickerConfig(cfg)
        player.start(cfg)
        toast(getString(R.string.clicker_toast_started))
        finish()
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private fun render() {
        binding.pickerView.setPoints(points)
        binding.tvPointCount.text = getString(R.string.clicker_point_count, points.size)
        renderRunningState(player.isRunning)
    }

    private fun renderRunningState(running: Boolean) {
        binding.btnStart.setText(
            if (running) R.string.clicker_btn_stop else R.string.clicker_btn_start,
        )
        binding.tvRunning.text = getString(
            if (running) R.string.clicker_running else R.string.clicker_idle,
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
