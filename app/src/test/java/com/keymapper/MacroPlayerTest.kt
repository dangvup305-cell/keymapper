package com.keymapper

import android.view.KeyEvent
import com.keymapper.domain.model.Macro
import com.keymapper.domain.model.MacroStep
import com.keymapper.domain.model.Point
import com.keymapper.engine.GestureInjector
import com.keymapper.engine.MacroPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宏回放时序 / holdMs / repeat 单元测试（P1-3）。
 *
 * 在 `runTest` 虚拟时间下调用 [MacroPlayer.playSuspend]，验证：
 * - 步骤按声明顺序执行
 * - KEY_DOWN 带 holdMs 时走长按注入，holdMs=0 时走点击
 * - repeat 控制单次步骤重复次数
 * - KEY_UP / WAIT 不产生手势注入
 */
class MacroPlayerTest {

    private class Rec : GestureInjector {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val longs = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<List<Int>>()
        override fun isInjectorReady(): Boolean = true
        override fun injectClick(x: Int, y: Int, durationMs: Long): Boolean {
            clicks.add(x to y)
            return true
        }

        override fun injectLongPress(x: Int, y: Int, durationMs: Long): Boolean {
            longs.add(x to y)
            return true
        }

        override fun injectSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
            swipes.add(listOf(fromX, fromY, toX, toY))
            return true
        }

        override fun injectPinch(focalX: Int, focalY: Int, scaleFactor: Float, durationMs: Long, radius: Int): Boolean = true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun play_steps_inOrder_withRepeat() = runTest {
        val rec = Rec()
        val player = MacroPlayer()
        player.attach(rec)
        // 任意 KEY_DOWN 都解析到 (10, 20)
        player.keyPointResolver = { Point(10, 20) }

        val macro = Macro(
            steps = listOf(
                MacroStep.keyDown(KeyEvent.KEYCODE_W, holdMs = 500), // holdMs>阈值 -> 长按
                MacroStep.wait(100), // 纯等待，无手势
                MacroStep.click(100, 200), // 点击 1 次
                MacroStep.keyUp(KeyEvent.KEYCODE_W), // 松开标记，无手势
                MacroStep.click(300, 400, delayMs = 50).copy(repeat = 3), // 点击 3 次
            ),
        )
        player.playSuspend(macro)

        // KEY_DOWN(500ms) -> 长按 1 次
        assertEquals(1, rec.longs.size)
        assertEquals(Point(10, 20), Point(rec.longs[0].first, rec.longs[0].second))
        // 点击：1 + 3 = 4 次
        assertEquals(4, rec.clicks.size)
        assertEquals(100 to 200, rec.clicks[0])
        assertEquals(300 to 400, rec.clicks[1])
        assertEquals(300 to 400, rec.clicks[2])
        assertEquals(300 to 400, rec.clicks[3])
        // 无滑动
        assertEquals(0, rec.swipes.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun shortKeyDown_usesClick() = runTest {
        val rec = Rec()
        val player = MacroPlayer()
        player.attach(rec)
        player.keyPointResolver = { Point(5, 5) }

        val macro = Macro(steps = listOf(MacroStep.keyDown(KeyEvent.KEYCODE_W, holdMs = 0)))
        player.playSuspend(macro)

        // holdMs=0 走点击而非长按
        assertEquals(1, rec.clicks.size)
        assertEquals(0, rec.longs.size)
    }
}
