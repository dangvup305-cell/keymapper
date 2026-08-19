package com.keymapper.ui.macro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keymapper.R
import com.keymapper.databinding.ActivityMacroEditorBinding
import com.keymapper.domain.model.Macro
import com.keymapper.domain.model.MacroStep
import com.keymapper.domain.model.StepAction
import com.keymapper.util.Constants

/**
 * 宏编辑器（功能3：一次性序列，如换装备）。
 *
 * 交互：
 * - 在覆盖层上**点击**添加「点击」步骤，**按住拖动**添加「滑动」步骤；
 * - 底部面板可统一调整步骤间隔（对全部步骤生效）、删除单个步骤；
 * - 「完成」把步骤序列打包为 [Macro] JSON 通过 [Activity.RESULT_OK] 回传，
 *   由 [com.keymapper.ui.mapping.MappingOverlayActivity] 写回绑定。
 *
 * 坐标统一为屏幕绝对像素（与 [com.keymapper.ui.mapping.CrosshairView] 一致）。
 */
class MacroEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMacroEditorBinding

    private val steps = mutableListOf<MacroStep>()

    private var bindingKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 覆盖层铺满屏幕，保证 rawX/rawY 与局部坐标一致（与映射模式一致）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMacroEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindingKey = intent.getStringExtra(Constants.EXTRA_BINDING_KEY).orEmpty()
        binding.tvBindingKey.text =
            if (bindingKey.isBlank()) "" else getString(R.string.macro_binding_key, bindingKey)

        // 载入已有宏作为编辑起点（编辑现有序列）
        intent.getStringExtra(Constants.EXTRA_MACRO_JSON)?.let { json ->
            try {
                steps.addAll(Macro.fromJson(json).steps)
            } catch (ignored: Exception) {
                // 非法宏直接忽略，从空序列开始
            }
        }
        if (steps.isNotEmpty()) {
            binding.etDelay.setText(steps.first().safeDelayMs.toString())
        }

        binding.macroView.onStepAdded = { step ->
            steps.add(step)
            renderSteps()
        }

        binding.btnClear.setOnClickListener {
            steps.clear()
            renderSteps()
        }
        binding.btnDone.setOnClickListener { saveAndFinish() }
        binding.btnCancel.setOnClickListener { finish() }

        setupDelayListener()
        renderSteps()
    }

    // ------------------------------------------------------------------
    // 步骤间隔（统一应用到全部步骤）
    // ------------------------------------------------------------------

    private var applyingDelay = false

    private fun setupDelayListener() {
        binding.etDelay.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (applyingDelay) return
                val ms = s?.toString()?.toLongOrNull() ?: return
                val safe = ms.coerceIn(0L, 5000L)
                for (i in steps.indices) {
                    steps[i] = steps[i].copy(delayMs = safe)
                }
                refreshSummary()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private fun renderSteps() {
        binding.macroView.setSteps(steps)
        binding.stepList.removeAllViews()
        steps.forEachIndexed { index, step ->
            binding.stepList.addView(buildStepRow(index, step))
        }
        refreshSummary()
    }

    private fun refreshSummary() {
        val total = steps.sumOf { it.totalDurationMs() }
        binding.tvStepCount.text = getString(R.string.macro_step_count, steps.size, total)
    }

    /** 单个步骤行：描述 + 删除按钮。 */
    private fun buildStepRow(index: Int, step: MacroStep): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (resources.displayMetrics.density * 8).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val desc = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = when (step.action) {
                StepAction.CLICK -> getString(
                    R.string.macro_step_click,
                    step.x ?: 0,
                    step.y ?: 0,
                )
                StepAction.SWIPE -> getString(
                    R.string.macro_step_swipe,
                    step.x ?: 0,
                    step.y ?: 0,
                    step.toX ?: 0,
                    step.toY ?: 0,
                )
                else -> getString(R.string.macro_step_wait, step.safeDelayMs)
            }
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val delete = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFF7043.toInt())
            textSize = 16f
            val pad = (resources.displayMetrics.density * 10).toInt()
            setPadding(pad, 0, pad, 0)
            setOnClickListener {
                steps.removeAt(index)
                renderSteps()
            }
        }
        row.addView(desc)
        row.addView(delete)
        return row
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private fun saveAndFinish() {
        if (steps.isEmpty()) {
            Toast.makeText(this, R.string.macro_toast_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val macro = Macro(name = bindingKey, steps = steps.toList())
        val data = Intent().putExtra(Constants.EXTRA_MACRO_JSON, macro.toJson())
        setResult(Activity.RESULT_OK, data)
        finish()
    }
}
