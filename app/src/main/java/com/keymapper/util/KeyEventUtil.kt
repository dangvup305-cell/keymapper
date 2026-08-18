package com.keymapper.util

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent

/**
 * [KeyEvent] 键码与可读标签之间的转换工具（P1-2）。
 *
 * 设计说明：
 * - 只使用 `KeyEvent.KEYCODE_*` 编译期常量（会被内联），因此本类可以在纯 JVM 单元测试中直接使用，
 *   不依赖 Android 运行时（不调用 `KeyEvent.keyCodeToString()` 之类的实例/静态方法）。
 * - [signature] 是"按键签名"的**唯一**生成入口，[com.keymapper.domain.model.KeyBinding] 与
 *   [com.keymapper.engine.MappingEngine] 都必须通过它生成/查找 map 的 key，保证两侧一致。
 */
object KeyEventUtil {

    /** 左右成对的修饰键：右键归一化为左键，使绑定 Ctrl 时左右 Ctrl 均可触发。 */
    private val MODIFIER_ALIAS: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_CTRL_RIGHT to KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT to KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT to KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_META_RIGHT to KeyEvent.KEYCODE_META_LEFT,
    )

    /** 全部被视为修饰键的键码。 */
    private val MODIFIERS: Set<Int> = setOf(
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_FUNCTION,
        KeyEvent.KEYCODE_SYM,
    )

    /** 系统保留键：无障碍服务无法可靠拦截，禁止参与绑定。 */
    private val RESERVED: Set<Int> = setOf(
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    /** 键码 → 可读标签。 */
    private val LABELS: Map<Int, String> = buildMap {
        // 字母 A-Z（KEYCODE_A..KEYCODE_Z 连续）
        for (i in 0..25) {
            put(KeyEvent.KEYCODE_A + i, ('A' + i).toString())
        }
        // 数字 0-9（KEYCODE_0..KEYCODE_9 连续）
        for (i in 0..9) {
            put(KeyEvent.KEYCODE_0 + i, i.toString())
        }
        // 小键盘 0-9
        for (i in 0..9) {
            put(KeyEvent.KEYCODE_NUMPAD_0 + i, "Num$i")
        }
        // F1-F12
        for (i in 0..11) {
            put(KeyEvent.KEYCODE_F1 + i, "F${i + 1}")
        }
        // 修饰键
        put(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl")
        put(KeyEvent.KEYCODE_CTRL_RIGHT, "Ctrl")
        put(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift")
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, "Shift")
        put(KeyEvent.KEYCODE_ALT_LEFT, "Alt")
        put(KeyEvent.KEYCODE_ALT_RIGHT, "Alt")
        put(KeyEvent.KEYCODE_META_LEFT, "Meta")
        put(KeyEvent.KEYCODE_META_RIGHT, "Meta")
        put(KeyEvent.KEYCODE_FUNCTION, "Fn")
        put(KeyEvent.KEYCODE_SYM, "Sym")
        // 控制键
        put(KeyEvent.KEYCODE_SPACE, "Space")
        put(KeyEvent.KEYCODE_ENTER, "Enter")
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, "NumEnter")
        put(KeyEvent.KEYCODE_TAB, "Tab")
        put(KeyEvent.KEYCODE_DEL, "Backspace")
        put(KeyEvent.KEYCODE_FORWARD_DEL, "Delete")
        put(KeyEvent.KEYCODE_ESCAPE, "Esc")
        put(KeyEvent.KEYCODE_CAPS_LOCK, "CapsLock")
        put(KeyEvent.KEYCODE_NUM_LOCK, "NumLock")
        put(KeyEvent.KEYCODE_SCROLL_LOCK, "ScrollLock")
        put(KeyEvent.KEYCODE_INSERT, "Insert")
        put(KeyEvent.KEYCODE_BREAK, "Pause")
        // 方向与翻页
        put(KeyEvent.KEYCODE_DPAD_UP, "↑")
        put(KeyEvent.KEYCODE_DPAD_DOWN, "↓")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "←")
        put(KeyEvent.KEYCODE_DPAD_RIGHT, "→")
        put(KeyEvent.KEYCODE_DPAD_CENTER, "OK")
        put(KeyEvent.KEYCODE_PAGE_UP, "PageUp")
        put(KeyEvent.KEYCODE_PAGE_DOWN, "PageDown")
        put(KeyEvent.KEYCODE_MOVE_HOME, "Home")
        put(KeyEvent.KEYCODE_MOVE_END, "End")
        // 符号
        put(KeyEvent.KEYCODE_GRAVE, "`")
        put(KeyEvent.KEYCODE_MINUS, "-")
        put(KeyEvent.KEYCODE_EQUALS, "=")
        put(KeyEvent.KEYCODE_LEFT_BRACKET, "[")
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, "]")
        put(KeyEvent.KEYCODE_BACKSLASH, "\\")
        put(KeyEvent.KEYCODE_SEMICOLON, ";")
        put(KeyEvent.KEYCODE_APOSTROPHE, "'")
        put(KeyEvent.KEYCODE_COMMA, ",")
        put(KeyEvent.KEYCODE_PERIOD, ".")
        put(KeyEvent.KEYCODE_SLASH, "/")
        put(KeyEvent.KEYCODE_NUMPAD_ADD, "Num+")
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "Num-")
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "Num*")
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "Num/")
        put(KeyEvent.KEYCODE_NUMPAD_DOT, "Num.")
        // 鼠标按键
        put(KeyEvent.KEYCODE_BUTTON_1, "Mouse1")
        put(KeyEvent.KEYCODE_BUTTON_2, "Mouse2")
        put(KeyEvent.KEYCODE_BUTTON_3, "Mouse3")
        // P2-2 滚轮伪键码
        put(Constants.KEYCODE_WHEEL_UP, "WheelUp")
        put(Constants.KEYCODE_WHEEL_DOWN, "WheelDown")
    }

    /** 修饰键排在前面，其余按键码升序，保证同一组合键始终得到同一签名。 */
    private val SIGNATURE_ORDER: Comparator<Int> = compareBy({ if (isModifier(it)) 0 else 1 }, { it })

    /**
     * 右侧修饰键归一化为左侧；非修饰键原样返回。
     */
    fun normalize(keyCode: Int): Int = MODIFIER_ALIAS[keyCode] ?: keyCode

    /** 是否为修饰键。 */
    fun isModifier(keyCode: Int): Boolean = keyCode in MODIFIERS

    /** 是否为系统保留键（不可绑定）。 */
    fun isReserved(keyCode: Int): Boolean = keyCode in RESERVED

    /** 是否为滚轮伪键码（P2-2）。 */
    fun isWheel(keyCode: Int): Boolean =
        keyCode == Constants.KEYCODE_WHEEL_UP || keyCode == Constants.KEYCODE_WHEEL_DOWN

    /**
     * 生成按键签名：绑定与匹配双方都用它作为 map key。
     *
     * @param keyCode 主键键码
     * @param combination 组合键列表；为 null/空时退化为单键签名
     */
    fun signature(keyCode: Int, combination: List<Int>?): List<Int> =
        if (combination.isNullOrEmpty()) {
            listOf(normalize(keyCode))
        } else {
            combination.map { normalize(it) }.distinct().sortedWith(SIGNATURE_ORDER)
        }

    /** 由一组同时按下的键码生成签名。 */
    fun signatureOf(keyCodes: Collection<Int>): List<Int> =
        keyCodes.map { normalize(it) }.distinct().sortedWith(SIGNATURE_ORDER)

    /** 单个键码 → 可读标签，未知键码回退为 `Key<code>`。 */
    fun keyLabel(keyCode: Int): String = LABELS[keyCode] ?: "Key$keyCode"

    /** 组合键 → 可读标签，如 `Ctrl+1`。 */
    fun comboLabel(keyCodes: List<Int>): String =
        keyCodes.map { normalize(it) }
            .distinct()
            .sortedWith(SIGNATURE_ORDER)
            .joinToString("+") { keyLabel(it) }

    /**
     * 绑定的显示文案：有组合键优先显示组合键，否则显示单键。
     */
    fun displayLabel(keyCode: Int, combination: List<Int>?): String =
        if (combination.isNullOrEmpty()) keyLabel(normalize(keyCode)) else comboLabel(combination)

    /**
     * 设备是否连接了鼠标（P2-2 无鼠标设备时优雅隐藏滚轮能力）。
     *
     * 注意：依赖 Android 运行时，仅可在 Activity/Service 中调用。
     */
    fun hasMouseDevice(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        val sources = device.sources
        (sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
    }

    /**
     * 设备是否连接了物理键盘。
     *
     * @param context 用于兜底读取配置（部分 ROM 上 InputDevice 枚举不全）
     */
    fun hasHardwareKeyboard(context: Context): Boolean {
        val byConfig = context.resources.configuration.keyboard !=
            android.content.res.Configuration.KEYBOARD_NOKEYS
        if (byConfig) return true
        return InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && !device.isVirtual
        }
    }
}
