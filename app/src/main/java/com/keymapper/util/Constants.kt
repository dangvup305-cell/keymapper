package com.keymapper.util

/**
 * 全局常量集中定义。
 *
 * 跨文件约定（见 ARCHITECTURE.md 第七节）：
 * - 广播 Action 统一前缀 `com.keymapper.action.`
 * - Intent Extra Key 统一前缀 `EXTRA_KEYMAPPER_`
 * - 坐标统一为屏幕绝对像素（px）
 * - 颜色统一为 hex 字符串
 *
 * 所有发送方与接收方都必须引用本文件的常量，禁止在业务代码里写字符串字面量。
 */
object Constants {

    // ---------------------------------------------------------------------
    // 广播 Action（前缀：com.keymapper.action.）
    // ---------------------------------------------------------------------

    /** 请求进入映射模式（发送方：悬浮窗面板 / MainActivity；接收方：FloatingWindowService） */
    const val ACTION_START_MAPPING = "com.keymapper.action.START_MAPPING"

    /** 请求退出映射模式 */
    const val ACTION_STOP_MAPPING = "com.keymapper.action.STOP_MAPPING"

    /** 切换配置：携带 [EXTRA_KEYMAPPER_CONFIG_NAME] */
    const val ACTION_SWITCH_CONFIG = "com.keymapper.action.SWITCH_CONFIG"

    /** 配置发生变化（新增/删除/覆盖保存），UI 需要刷新列表 */
    const val ACTION_CONFIG_CHANGED = "com.keymapper.action.CONFIG_CHANGED"

    /** 当前生效绑定已更新（引擎重新 loadBindings 之后），标记层需要重绘 */
    const val ACTION_BINDINGS_UPDATED = "com.keymapper.action.BINDINGS_UPDATED"

    /** 开启测试模式（P1-5 调试高亮） */
    const val ACTION_TEST_MODE_ON = "com.keymapper.action.TEST_MODE_ON"

    /** 关闭测试模式 */
    const val ACTION_TEST_MODE_OFF = "com.keymapper.action.TEST_MODE_OFF"

    /** 低电量状态变化（P2-3）：携带 [EXTRA_KEYMAPPER_ENABLED] */
    const val ACTION_LOW_BATTERY = "com.keymapper.action.LOW_BATTERY"

    /**
     * 某个绑定被触发：携带 [EXTRA_KEYMAPPER_KEY_CODE] / [EXTRA_KEYMAPPER_X] /
     * [EXTRA_KEYMAPPER_Y] / [EXTRA_KEYMAPPER_COLOR] / [EXTRA_KEYMAPPER_LABEL]。
     *
     * **进程内不使用本广播**：按键触发处于「按下→注入」的关键路径上，
     * 每次按键走一次跨进程广播会带来不可接受的延迟，因此调试高亮（P1-5）改用
     * [com.keymapper.engine.MappingEngine.addTriggerListener] 的进程内回调。
     * 本 Action 保留为**对外可观测接口**（如自动化测试 / 外部调试工具订阅），当前无内部发送方。
     */
    const val ACTION_KEY_TRIGGERED = "com.keymapper.action.KEY_TRIGGERED"

    /**
     * 显示映射标记层（P0-4）。
     * 悬浮面板内部直接调用服务方法切换，本 Action 作为外部/后续入口保留，当前无内部发送方。
     */
    const val ACTION_SHOW_MARKERS = "com.keymapper.action.SHOW_MARKERS"

    /** 隐藏映射标记层（同 [ACTION_SHOW_MARKERS]，保留外部入口） */
    const val ACTION_HIDE_MARKERS = "com.keymapper.action.HIDE_MARKERS"

    /** 显示悬浮球 */
    const val ACTION_SHOW_FLOATING = "com.keymapper.action.SHOW_FLOATING"

    /** 隐藏悬浮球（进入映射模式时避免遮挡） */
    const val ACTION_HIDE_FLOATING = "com.keymapper.action.HIDE_FLOATING"

    /** 保活服务自重启信号（KeepAliveService.onDestroy → AlarmManager 回调） */
    const val ACTION_RESTART_SERVICE = "com.keymapper.action.RESTART_SERVICE"

    /**
     * 打开配置面板。悬浮面板经「设置页 → 配置管理」进入，本 Action 保留为直达入口，
     * 当前无内部发送方。
     */
    const val ACTION_OPEN_CONFIG_PANEL = "com.keymapper.action.OPEN_CONFIG_PANEL"

    /** 打开设置 / 权限引导页（同 [ACTION_OPEN_CONFIG_PANEL]，保留直达入口） */
    const val ACTION_OPEN_SETTINGS = "com.keymapper.action.OPEN_SETTINGS"

    // ---------------------------------------------------------------------
    // Intent Extra Key（前缀：EXTRA_KEYMAPPER_）
    //
    // 使用中：CONFIG_NAME（ACTION_SWITCH_CONFIG）、ENABLED（ACTION_LOW_BATTERY）
    // 其余为 [ACTION_KEY_TRIGGERED] 的载荷定义，随该 Action 一并保留（见其注释）
    // ---------------------------------------------------------------------

    const val EXTRA_KEYMAPPER_CONFIG_NAME = "EXTRA_KEYMAPPER_CONFIG_NAME"
    const val EXTRA_KEYMAPPER_KEY_CODE = "EXTRA_KEYMAPPER_KEY_CODE"
    const val EXTRA_KEYMAPPER_X = "EXTRA_KEYMAPPER_X"
    const val EXTRA_KEYMAPPER_Y = "EXTRA_KEYMAPPER_Y"
    const val EXTRA_KEYMAPPER_LABEL = "EXTRA_KEYMAPPER_LABEL"
    const val EXTRA_KEYMAPPER_COLOR = "EXTRA_KEYMAPPER_COLOR"
    const val EXTRA_KEYMAPPER_ENABLED = "EXTRA_KEYMAPPER_ENABLED"
    const val EXTRA_KEYMAPPER_ACTION_TYPE = "EXTRA_KEYMAPPER_ACTION_TYPE"
    const val EXTRA_KEYMAPPER_FROM_FLOATING = "EXTRA_KEYMAPPER_FROM_FLOATING"

    /** 宏编辑器传入/回传的宏 JSON（[MacroEditorActivity] 使用） */
    const val EXTRA_MACRO_JSON = "EXTRA_MACRO_JSON"

    /** 宏编辑器关联的绑定键显示文案（仅展示用） */
    const val EXTRA_BINDING_KEY = "EXTRA_BINDING_KEY"

    // ---------------------------------------------------------------------
    // SharedPreferences
    // ---------------------------------------------------------------------

    const val PREFS_NAME = "keymapper_prefs"
    const val PREF_ACTIVE_CONFIG = "pref_active_config"
    const val PREF_AUTO_SWITCH_BY_PACKAGE = "pref_auto_switch_by_package"
    const val PREF_LOW_BATTERY_SAVER = "pref_low_battery_saver"
    const val PREF_FIRST_LAUNCH_DONE = "pref_first_launch_done"
    const val PREF_MARKERS_VISIBLE = "pref_markers_visible"

    // ---------------------------------------------------------------------
    // 配置存储
    // ---------------------------------------------------------------------

    /** 配置目录：Context.getFilesDir()/configs/ */
    const val CONFIG_DIR_NAME = "configs"
    const val CONFIG_FILE_EXTENSION = ".json"
    const val CONFIG_MIME_TYPE = "application/json"

    // ---------------------------------------------------------------------
    // 时序参数
    // ---------------------------------------------------------------------

    /** 宏时间粒度：50ms（Q2 决议） */
    const val MACRO_GRANULARITY_MS = 50L

    /** 长按连发（按住持续触发）默认间隔：100ms */
    const val DEFAULT_REPEAT_INTERVAL_MS = 100L

    /** 长按连发最小间隔，避免过密导致系统丢弃手势 */
    const val MIN_REPEAT_INTERVAL_MS = 20L

    /** 标记点默认半径（dp），绑定未单独设置半径时使用 */
    const val DEFAULT_MARKER_RADIUS_DP = 12

    /** 网格吸附默认间距（dp），配置未显式设置时使用 */
    const val DEFAULT_GRID_SIZE_DP = 20

    /** 普通点击的手势时长 */
    const val DEFAULT_CLICK_DURATION_MS = 40L

    /** 长按默认时长 */
    const val DEFAULT_LONG_PRESS_MS = 600L

    /** 滑动默认时长 */
    const val DEFAULT_SWIPE_DURATION_MS = 300L

    /** 双指缩放默认时长 */
    const val DEFAULT_PINCH_DURATION_MS = 400L

    /** 单个手势最大时长（AccessibilityService 限制约 60s，这里保守限制） */
    const val MAX_GESTURE_DURATION_MS = 30_000L

    /** 捕获按键的等待超时 */
    const val KEY_CAPTURE_TIMEOUT_MS = 15_000L

    // ---------------------------------------------------------------------
    // 通知（P0-6 保活）
    // ---------------------------------------------------------------------

    const val NOTIFICATION_CHANNEL_ID = "keymapper_keep_alive"
    const val NOTIFICATION_ID = 1001

    /** onDestroy 后尝试重启自身的默认延时（可被 keep_alive_service_config.xml 覆盖） */
    const val SERVICE_RESTART_DELAY_MS = 1500L

    // ---------------------------------------------------------------------
    // 悬浮窗刷新（P2-3 低电量降频）
    // ---------------------------------------------------------------------

    const val NORMAL_HIGHLIGHT_FRAME_MS = 16L
    const val LOW_BATTERY_HIGHLIGHT_FRAME_MS = 100L
    const val LOW_BATTERY_THRESHOLD_PERCENT = 20

    /** 调试高亮显示时长 */
    const val HIGHLIGHT_DURATION_MS = 600L

    // ---------------------------------------------------------------------
    // 外观
    // ---------------------------------------------------------------------

    /** 默认标签颜色（hex 字符串），与 colors.xml 的 label_deep_orange 一致 */
    const val DEFAULT_LABEL_COLOR = "#FF5722"

    /** 代码内默认色板，与 colors.xml 的 label_color_palette 顺序一致 */
    val LABEL_COLOR_PALETTE: List<String> = listOf(
        "#FF5722",
        "#2196F3",
        "#4CAF50",
        "#9C27B0",
        "#FFC107",
        "#00BCD4",
        "#E91E63",
        "#009688",
    )

    // ---------------------------------------------------------------------
    // 伪键码（P2-2 鼠标滚轮）
    // ---------------------------------------------------------------------

    /**
     * 鼠标滚轮没有对应的 Android KeyEvent 常量（滚轮以 MotionEvent.ACTION_SCROLL 上报），
     * 因此使用远高于系统键码上限的伪键码参与绑定与匹配。
     */
    const val KEYCODE_WHEEL_UP = 10_001
    const val KEYCODE_WHEEL_DOWN = 10_002
}

/**
 * 映射引擎状态机。
 *
 * - [IDLE]      未加载任何绑定，不处理按键
 * - [RUNNING]   正常运行，命中绑定即注入手势
 * - [CAPTURING] 绑定采集态，截获下一次按键并回调给 UI（不注入手势）
 * - [TEST_MODE] 测试模式，命中绑定只高亮不注入（P1-5）
 */
enum class EngineState {
    IDLE,
    RUNNING,
    CAPTURING,
    TEST_MODE,
}
