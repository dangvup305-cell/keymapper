package com.keymapper

import android.view.KeyEvent
import com.keymapper.domain.model.KeyBinding
import com.keymapper.domain.model.MappingConfig
import com.keymapper.engine.GestureInjector
import com.keymapper.engine.MacroPlayer
import com.keymapper.engine.MappingEngine
import com.keymapper.util.ResolutionScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 映射引擎单键 / 组合键匹配状态机单元测试（P1-2）。
 *
 * 直接调用纯函数入口 [MappingEngine.handleKey]，不依赖 Android [KeyEvent] 实例；
 * 用 [FakeInjector] 记录注入动作，验证匹配与派发是否正确。
 */
class MappingEngineTest {

    /** 记录注入动作的假实现，供断言。 */
    private class FakeInjector : GestureInjector {
        val clicks = mutableListOf<Pair<Int, Int>>()
        val longPresses = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<List<Int>>()
        val pinches = mutableListOf<Int>()
        override fun isInjectorReady(): Boolean = true
        override fun injectClick(x: Int, y: Int, durationMs: Long): Boolean {
            clicks.add(x to y)
            return true
        }

        override fun injectLongPress(x: Int, y: Int, durationMs: Long): Boolean {
            longPresses.add(x to y)
            return true
        }

        override fun injectSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
            swipes.add(listOf(fromX, fromY, toX, toY))
            return true
        }

        override fun injectPinch(focalX: Int, focalY: Int, scaleFactor: Float, durationMs: Long, radius: Int): Boolean {
            pinches.add(radius)
            return true
        }
    }

    private lateinit var engine: MappingEngine
    private lateinit var injector: FakeInjector

    @Before
    fun setup() {
        injector = FakeInjector()
        engine = MappingEngine(ResolutionScaler(), MacroPlayer())
        engine.attach(injector)
    }

    @Test
    fun singleKey_dispatchesClick() {
        engine.loadBindings(
            MappingConfig(bindings = listOf(KeyBinding(x = 100, y = 200, keyCode = KeyEvent.KEYCODE_A))),
        )
        assertTrue(engine.handleKey(KeyEvent.KEYCODE_A, true))
        assertEquals(1, injector.clicks.size)
        assertEquals(100 to 200, injector.clicks[0])
        // 抬起事件同样被消费（MappingEngine 设计：被消费 DOWN 的键，其 UP 也一并消费，
        // 避免前台应用收到孤立的 UP），但不再注入手势
        assertTrue(engine.handleKey(KeyEvent.KEYCODE_A, false))
        assertEquals(1, injector.clicks.size)
    }

    @Test
    fun comboKey_dispatchesOnlyWhenBothPressed() {
        engine.loadBindings(
            MappingConfig(
                bindings = listOf(
                    KeyBinding(
                        x = 50,
                        y = 60,
                        keyCode = KeyEvent.KEYCODE_1,
                        keyCombination = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_1),
                    ),
                ),
            ),
        )
        // 单按 '1' 不应触发（只有组合键绑定存在）
        assertFalse(engine.handleKey(KeyEvent.KEYCODE_1, true))
        assertFalse(engine.handleKey(KeyEvent.KEYCODE_1, false))
        // 真实组合键顺序：Ctrl 然后 1
        assertFalse(engine.handleKey(KeyEvent.KEYCODE_CTRL_LEFT, true))
        assertTrue(engine.handleKey(KeyEvent.KEYCODE_1, true))
        assertEquals(1, injector.clicks.size)
        assertEquals(50 to 60, injector.clicks[0])
    }

    @Test
    fun reservedKey_passesThrough() {
        engine.loadBindings(
            MappingConfig(bindings = listOf(KeyBinding(x = 1, y = 1, keyCode = KeyEvent.KEYCODE_A))),
        )
        // HOME 是系统保留键，引擎透传
        assertFalse(engine.handleKey(KeyEvent.KEYCODE_HOME, true))
    }

    @Test
    fun repeatCount_doesNotRetrigger() {
        engine.loadBindings(
            MappingConfig(bindings = listOf(KeyBinding(x = 1, y = 1, keyCode = KeyEvent.KEYCODE_A))),
        )
        assertTrue(engine.handleKey(KeyEvent.KEYCODE_A, true, repeatCount = 0))
        // 长按自动重复（repeatCount > 0）被消费但不重新派发，避免前台应用收到按键刷屏
        assertTrue(engine.handleKey(KeyEvent.KEYCODE_A, true, repeatCount = 1))
        assertEquals(1, injector.clicks.size)
    }

    @Test
    fun captureMode_singleKey() {
        var captured: Pair<Int, List<Int>?>? = null
        engine.startCaptureMode { k, c -> captured = k to c }
        engine.handleKey(KeyEvent.KEYCODE_B, true)
        assertEquals(KeyEvent.KEYCODE_B, captured?.first)
        assertNull(captured?.second)
    }

    @Test
    fun captureMode_combo() {
        var captured: Pair<Int, List<Int>?>? = null
        engine.startCaptureMode { k, c -> captured = k to c }
        engine.handleKey(KeyEvent.KEYCODE_CTRL_LEFT, true)
        engine.handleKey(KeyEvent.KEYCODE_2, true)
        assertEquals(KeyEvent.KEYCODE_2, captured?.first)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_2), captured?.second)
    }
}
