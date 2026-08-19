package com.keymapper.ui.mapping

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.keymapper.R
import com.keymapper.data.ConfigRepository
import com.keymapper.databinding.ActivityMappingOverlayBinding
import com.keymapper.domain.model.ActionType
import com.keymapper.domain.model.GestureAction
import com.keymapper.domain.model.KeyBinding
import com.keymapper.domain.model.Macro
import com.keymapper.domain.model.MappingConfig
import com.keymapper.domain.model.Point
import com.keymapper.engine.MappingEngine
import com.keymapper.ui.macro.MacroEditorActivity
import com.keymapper.util.Constants
import com.keymapper.util.KeyEventUtil
import com.keymapper.util.ResolutionScaler
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 映射模式覆盖层宿主（P0-2 / P0-3 / P0-4 / P2-1）。
 *
 * 交互流程（对应 sequence-diagram 流程 a）：
 * 1. 全屏半透明覆盖层显示 [CrosshairView]；
 * 2. 用户点击屏幕 → 采集 `MotionEvent` 原始绝对坐标 → 显示十字准星并进入「等待按键」；
 * 3. 用户按下物理按键 → [MappingEngine] 捕获态回调键码（含组合键）→ 生成一条 [KeyBinding]；
 * 4. 重复 2-3 直到完成，点击「保存配置」命名落盘（P0-5）。
 *
 * 安全设计：覆盖层可见期间引擎**始终保持捕获态**，因此映射过程中的按键只会用于绑定，
 * 不会误注入手势（避免边映射边触发点击）。退出时恢复此前状态。
 */
class MappingOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMappingOverlayBinding

    private val engine: MappingEngine by inject()
    private val repository: ConfigRepository by inject()

    /** 正在编辑的配置（坐标为当前屏幕绝对像素）。 */
    private var working: MappingConfig = MappingConfig.empty()

    /** 待绑定坐标。 */
    private var pending: Point? = null

    /** 是否正在等待用户按键。 */
    private var awaitingKey = false

    /** 重新绑定按键时的旧绑定；为 null 表示新增。 */
    private var rebindTarget: KeyBinding? = null

    /** 正在为哪条绑定选择滑动终点（P2-1）。 */
    private var swipeSource: KeyBinding? = null

    /** 下一个新标记使用的色板下标。 */
    private var colorIndex = 0

    private var screenWidth = 0
    private var screenHeight = 0

    private val timeoutHandler = Handler(Looper.getMainLooper())

    private val captureTimeoutRunnable = Runnable { onCaptureTimeout() }

    /** 宏编辑器结果回传：把编辑好的宏写回正在编辑的绑定（功能3）。 */
    private val macroEditorLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val marker = macroEditTarget ?: return@registerForActivityResult
        val json = data.getStringExtra(Constants.EXTRA_MACRO_JSON) ?: return@registerForActivityResult
        macroEditTarget = null
        try {
            val macro = Macro.fromJson(json)
            val updated = marker.copy(actionType = ActionType.MACRO, macro = macro)
            working = working.upsertBinding(updated).first
            binding.crosshairView.setBindings(working.bindings)
            refreshCount()
            toast(getString(R.string.macro_toast_saved, macro.steps.size))
        } catch (e: Exception) {
            toast(getString(R.string.error_config_parse, e.message.orEmpty()))
        }
    }

    /** 正在用宏编辑器编辑的绑定（从标记菜单进入）。 */
    private var macroEditTarget: KeyBinding? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 映射覆盖层必须真正铺满屏幕，否则 rawX/rawY 与 View 局部坐标之间会差一个系统栏偏移
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        binding = ActivityMappingOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (width, height) = ResolutionScaler.realScreenSize(this)
        screenWidth = width
        screenHeight = height
        engine.setTargetResolution(width, height)

        binding.crosshairView.onPointPicked = { x, y -> onPointPicked(x, y) }
        binding.crosshairView.onMarkerLongPress = { marker -> showMarkerMenu(marker) }

        binding.btnGrid.setOnClickListener { toggleGridSnap() }
        binding.btnUndo.setOnClickListener { undoLast() }
        binding.btnSave.setOnClickListener { showSaveDialog() }
        binding.btnExit.setOnClickListener { exitMapping() }

        // 收起悬浮球，避免遮挡采点
        sendLocalBroadcast(Constants.ACTION_HIDE_FLOATING)

        loadInitialConfig()
        refreshHud()
    }

    override fun onStart() {
        super.onStart()
        armCapture()
    }

    override fun onStop() {
        timeoutHandler.removeCallbacks(captureTimeoutRunnable)
        engine.stopCaptureMode()
        super.onStop()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(captureTimeoutRunnable)
        engine.stopCaptureMode()
        // 通知悬浮窗服务：映射结束，恢复悬浮球与标记层
        sendLocalBroadcast(Constants.ACTION_STOP_MAPPING)
        sendLocalBroadcast(Constants.ACTION_SHOW_FLOATING)
        super.onDestroy()
    }

    /**
     * 兜底按键接收：无障碍服务未连接时，物理按键仍会派发到本 Activity，
     * 因此这里直接处理，保证「未开无障碍也能先把映射方案配好」（P0-8 引导前置体验）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!awaitingKey || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            return super.dispatchKeyEvent(event)
        }
        val normalized = KeyEventUtil.normalize(event.keyCode)
        if (KeyEventUtil.isReserved(normalized) || KeyEventUtil.isModifier(normalized)) {
            return super.dispatchKeyEvent(event)
        }
        val modifiers = modifiersOf(event)
        val combination = if (modifiers.isEmpty()) {
            null
        } else {
            KeyEventUtil.signatureOf(modifiers + normalized)
        }
        onKeyCaptured(normalized, combination)
        return true
    }

    /** 从 [KeyEvent] 的 metaState 还原当前按下的修饰键。 */
    private fun modifiersOf(event: KeyEvent): List<Int> {
        val modifiers = mutableListOf<Int>()
        if (event.isCtrlPressed) modifiers.add(KeyEvent.KEYCODE_CTRL_LEFT)
        if (event.isShiftPressed) modifiers.add(KeyEvent.KEYCODE_SHIFT_LEFT)
        if (event.isAltPressed) modifiers.add(KeyEvent.KEYCODE_ALT_LEFT)
        if (event.isMetaPressed) modifiers.add(KeyEvent.KEYCODE_META_LEFT)
        return modifiers
    }

    // ------------------------------------------------------------------
    // 初始配置
    // ------------------------------------------------------------------

    /**
     * 载入当前生效配置作为编辑起点（按当前分辨率缩放），便于在已有方案上继续追加映射点。
     * 没有生效配置时从空配置开始。
     */
    private fun loadInitialConfig() {
        lifecycleScope.launch {
            try {
                val active = repository.loadActiveConfig()
                working = active?.scaledCopy(screenWidth, screenHeight)
                    ?: MappingConfig.empty(
                        name = "",
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                    )
            } catch (e: Exception) {
                Log.w(TAG, "载入生效配置失败，改用空配置：${e.message}")
                working = MappingConfig.empty(
                    name = "",
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                )
            }
            colorIndex = working.bindings.size
            binding.crosshairView.setBindings(working.bindings)
            syncGridState()
            refreshHud()
        }
    }

    /** 把 working 的网格吸附状态同步到采点视图与按钮文案。 */
    private fun syncGridState() {
        binding.crosshairView.snapToGrid = working.snapToGrid
        binding.crosshairView.gridSize = working.gridSize
        binding.btnGrid.setText(
            if (working.snapToGrid) R.string.mapping_btn_grid_on else R.string.mapping_btn_grid_off,
        )
    }

    /** 切换网格吸附（写入 working，保存配置时一并落盘）。 */
    private fun toggleGridSnap() {
        val enabled = !working.snapToGrid
        working = working.copy(
            snapToGrid = enabled,
            gridSize = if (working.gridSize > 0) working.gridSize else Constants.DEFAULT_GRID_SIZE_DP,
        )
        syncGridState()
        toast(getString(if (enabled) R.string.mapping_toast_grid_on else R.string.mapping_toast_grid_off))
    }

    // ------------------------------------------------------------------
    // 采点 / 绑定
    // ------------------------------------------------------------------

    /** 让引擎进入捕获态，截获下一次按键。 */
    private fun armCapture() {
        engine.startCaptureMode { keyCode, combination ->
            runOnUiThread { onKeyCaptured(keyCode, combination) }
        }
    }

    /** 采到一个坐标。 */
    private fun onPointPicked(x: Int, y: Int) {
        // 正在为某条绑定选滑动终点（P2-1）
        swipeSource?.let { source ->
            applySwipeTarget(source, Point(x, y))
            return
        }

        pending = Point(x, y)
        rebindTarget = null
        awaitingKey = true
        binding.crosshairView.setPending(x, y)
        binding.crosshairView.locked = true
        binding.tvHint.text = getString(R.string.mapping_hint_press_key, x, y)
        restartCaptureTimeout()
        armCapture()
    }

    /**
     * 捕获到按键：生成/更新绑定。
     *
     * @param keyCode 主键键码（已归一化）
     * @param combination 组合键签名，单键时为 null
     */
    private fun onKeyCaptured(keyCode: Int, combination: List<Int>?) {
        if (!awaitingKey) {
            // 未选点时的按键忽略，并重新武装捕获态
            armCapture()
            return
        }
        timeoutHandler.removeCallbacks(captureTimeoutRunnable)
        awaitingKey = false

        val point = pending
        if (point == null) {
            armCapture()
            return
        }

        val old = rebindTarget
        val candidate = if (old != null) {
            old.copy(keyCode = keyCode, keyCombination = combination)
        } else {
            KeyBinding(
                x = point.x,
                y = point.y,
                keyCode = keyCode,
                keyCombination = combination,
                labelColor = nextColor(),
                actionType = ActionType.CLICK,
            )
        }

        if (old != null) {
            working = working.removeBinding(old.signature())
        }
        val (updated, replaced) = working.upsertBinding(candidate)
        working = updated

        rebindTarget = null
        pending = null
        binding.crosshairView.locked = false
        binding.crosshairView.clearPending()
        binding.crosshairView.setBindings(working.bindings)

        binding.tvHint.text = getString(
            R.string.mapping_hint_bound,
            candidate.displayKey(),
            candidate.x,
            candidate.y,
        )
        refreshCount()

        if (replaced) {
            toast(getString(R.string.mapping_toast_duplicated, candidate.displayKey()))
        }
        armCapture()
    }

    /** 等待按键超时：放弃本次绑定。 */
    private fun onCaptureTimeout() {
        if (!awaitingKey) return
        awaitingKey = false
        pending = null
        rebindTarget = null
        binding.crosshairView.locked = false
        binding.crosshairView.clearPending()
        binding.tvHint.setText(R.string.mapping_hint_pick_point)
        toast(getString(R.string.mapping_toast_capture_timeout))
        armCapture()
    }

    private fun restartCaptureTimeout() {
        timeoutHandler.removeCallbacks(captureTimeoutRunnable)
        timeoutHandler.postDelayed(captureTimeoutRunnable, Constants.KEY_CAPTURE_TIMEOUT_MS)
    }

    /** 撤销最后一个映射点。 */
    private fun undoLast() {
        val last = working.bindings.lastOrNull()
        if (last == null) {
            toast(getString(R.string.mapping_toast_empty))
            return
        }
        working = working.removeBinding(last.signature())
        if (colorIndex > 0) colorIndex--
        binding.crosshairView.setBindings(working.bindings)
        refreshHud()
    }

    // ------------------------------------------------------------------
    // 标记菜单（P0-4 / P2-1）
    // ------------------------------------------------------------------

    /** 长按标记后的操作菜单。 */
    private fun showMarkerMenu(marker: KeyBinding) {
        val items = arrayOf(
            getString(R.string.mapping_marker_delete),
            getString(R.string.mapping_marker_rebind),
            getString(R.string.mapping_marker_change_color),
            getString(R.string.mapping_marker_change_action),
            getString(R.string.mapping_marker_size),
            if (marker.repeatEnabled) {
                getString(R.string.mapping_marker_repeat_off)
            } else {
                getString(R.string.mapping_marker_repeat_on)
            },
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapping_marker_menu_title, marker.displayKey()))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> deleteMarker(marker)
                    1 -> startRebind(marker)
                    2 -> showColorPicker(marker)
                    3 -> showActionTypePicker(marker)
                    4 -> showMarkerSizeDialog(marker)
                    5 -> toggleRepeat(marker)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 调整标记大小（功能1）：SeekBar 实时预览半径，确认后写回 working。 */
    private fun showMarkerSizeDialog(marker: KeyBinding) {
        val container = LinearLayout(this).apply {
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad / 2, pad, 0)
            orientation = LinearLayout.VERTICAL
        }
        val label = TextView(this).apply {
            text = getString(R.string.mapping_marker_size_value, marker.effectiveRadiusDp())
        }
        val seek = SeekBar(this).apply {
            max = 40
            progress = marker.effectiveRadiusDp().coerceIn(4, 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = getString(R.string.mapping_marker_size_value, value.coerceAtLeast(4))
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        container.addView(label)
        container.addView(seek)

        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_marker_size)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val updated = marker.copy(radius = seek.progress.coerceAtLeast(4))
                working = working.upsertBinding(updated).first
                binding.crosshairView.setBindings(working.bindings)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 切换长按连发（功能2）：按住物理键按间隔持续触发。 */
    private fun toggleRepeat(marker: KeyBinding) {
        val updated = marker.copy(repeatEnabled = !marker.repeatEnabled)
        working = working.upsertBinding(updated).first
        toast(
            getString(
                if (updated.repeatEnabled) R.string.mapping_toast_repeat_on else R.string.mapping_toast_repeat_off,
                updated.displayKey(),
            ),
        )
    }

    private fun deleteMarker(marker: KeyBinding) {
        working = working.removeBinding(marker.signature())
        binding.crosshairView.setBindings(working.bindings)
        refreshHud()
    }

    /** 重新绑定按键：坐标保持不变，等待新的按键。 */
    private fun startRebind(marker: KeyBinding) {
        rebindTarget = marker
        pending = Point(marker.x, marker.y)
        awaitingKey = true
        binding.crosshairView.setPending(marker.x, marker.y)
        binding.crosshairView.locked = true
        binding.tvHint.text = getString(R.string.mapping_hint_press_key, marker.x, marker.y)
        restartCaptureTimeout()
        armCapture()
    }

    /** 修改标签颜色（P0-4 色板取自 [Constants.LABEL_COLOR_PALETTE]）。 */
    private fun showColorPicker(marker: KeyBinding) {
        val palette = Constants.LABEL_COLOR_PALETTE
        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_marker_change_color)
            .setItems(palette.toTypedArray()) { _, which ->
                val updated = marker.copy(labelColor = palette[which])
                working = working.upsertBinding(updated).first
                binding.crosshairView.setBindings(working.bindings)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 修改触发类型（P2-1）。 */
    private fun showActionTypePicker(marker: KeyBinding) {
        val labels = arrayOf(
            getString(R.string.action_type_click),
            getString(R.string.action_type_longpress),
            getString(R.string.action_type_swipe),
            getString(R.string.action_type_pinch),
            getString(R.string.action_type_macro),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_marker_change_action)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> applyActionType(
                        marker,
                        ActionType.CLICK,
                        GestureAction(durationMs = Constants.DEFAULT_CLICK_DURATION_MS),
                    )

                    1 -> applyActionType(marker, ActionType.LONG_PRESS, GestureAction.longPress())

                    2 -> startSwipeTargetPick(marker)

                    3 -> applyActionType(
                        marker,
                        ActionType.PINCH,
                        GestureAction.pinch(
                            focal = Point(marker.x, marker.y),
                            scaleFactor = DEFAULT_PINCH_SCALE,
                        ),
                    )

                    4 -> startMacroEditor(marker)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 写入新的触发类型与手势参数。 */
    private fun applyActionType(
        marker: KeyBinding,
        type: ActionType,
        gesture: GestureAction?,
    ) {
        val updated = marker.copy(actionType = type, gestureParams = gesture)
        working = working.upsertBinding(updated).first
        binding.crosshairView.setBindings(working.bindings)
        refreshCount()
    }

    /** 进入「点击滑动终点」流程。 */
    private fun startSwipeTargetPick(marker: KeyBinding) {
        swipeSource = marker
        awaitingKey = false
        binding.crosshairView.locked = false
        binding.crosshairView.setPending(marker.x, marker.y)
        binding.tvHint.setText(R.string.mapping_hint_swipe_target)
    }

    /** 完成滑动终点选择。 */
    private fun applySwipeTarget(source: KeyBinding, target: Point) {
        swipeSource = null
        val gesture = GestureAction.swipe(
            from = Point(source.x, source.y),
            to = target,
        )
        applyActionType(source, ActionType.SWIPE, gesture)
        binding.crosshairView.clearPending()
        binding.tvHint.text = getString(
            R.string.mapping_hint_bound,
            source.displayKey(),
            source.x,
            source.y,
        )
    }

    // ------------------------------------------------------------------
    // 宏编辑器（功能3：一次性序列，如换装备）
    // ------------------------------------------------------------------

    /**
     * 打开宏编辑器：为 [marker] 创建/编辑一次性序列（点击/滑动步骤）。
     * 保存后回传 Macro JSON，自动把绑定切换为 MACRO 类型。
     */
    private fun startMacroEditor(marker: KeyBinding) {
        macroEditTarget = marker
        val intent = Intent(this, MacroEditorActivity::class.java).apply {
            putExtra(Constants.EXTRA_BINDING_KEY, marker.displayKey())
            marker.macro?.let { putExtra(Constants.EXTRA_MACRO_JSON, it.toJson()) }
        }
        macroEditorLauncher.launch(intent)
    }

    // ------------------------------------------------------------------
    // 保存 / 退出
    // ------------------------------------------------------------------

    /** 命名保存（P0-5）。 */
    private fun showSaveDialog() {
        if (working.bindings.isEmpty()) {
            toast(getString(R.string.mapping_toast_empty))
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setHint(R.string.mapping_save_dialog_hint)
            setText(working.configName)
            setSelection(working.configName.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.mapping_save_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(getString(R.string.mapping_toast_name_empty))
                } else {
                    saveConfig(name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveConfig(name: String) {
        val target = working.copy(
            configName = name,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            createdAt = if (working.createdAt > 0L) working.createdAt else System.currentTimeMillis(),
        ).sanitized()

        lifecycleScope.launch {
            try {
                repository.saveConfig(target)
                repository.setActiveConfigName(target.configName)
                engine.loadBindings(target)
                sendLocalBroadcast(Constants.ACTION_CONFIG_CHANGED)
                sendLocalBroadcast(Constants.ACTION_BINDINGS_UPDATED)
                toast(getString(R.string.mapping_toast_saved, target.configName))
                finish()
            } catch (e: Exception) {
                Log.w(TAG, "保存配置失败：${e.message}")
                toast(getString(R.string.error_config_parse, e.message.orEmpty()))
            }
        }
    }

    /** 退出映射模式。 */
    private fun exitMapping() {
        engine.stopCaptureMode()
        finish()
    }

    // ------------------------------------------------------------------
    // HUD 与工具
    // ------------------------------------------------------------------

    private fun refreshHud() {
        if (!awaitingKey && swipeSource == null) {
            binding.tvHint.setText(R.string.mapping_hint_pick_point)
        }
        refreshCount()
    }

    private fun refreshCount() {
        binding.tvCount.text = getString(R.string.mapping_binding_count, working.bindings.size)
    }

    /** 按色板顺序取下一个标签颜色。 */
    private fun nextColor(): String {
        val palette = Constants.LABEL_COLOR_PALETTE
        if (palette.isEmpty()) return Constants.DEFAULT_LABEL_COLOR
        val color = palette[colorIndex % palette.size]
        colorIndex++
        return color
    }

    private fun sendLocalBroadcast(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "KeyMapperOverlay"

        /** 双指缩放默认放大倍率。 */
        const val DEFAULT_PINCH_SCALE = 1.6f
    }
}
