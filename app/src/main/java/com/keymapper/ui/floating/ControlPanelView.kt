package com.keymapper.ui.floating

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.keymapper.R
import com.keymapper.databinding.ControlPanelBinding

/**
 * 悬浮操作面板（P0-1 展开态 / P1-1 快速切换入口）。
 *
 * 按钮语义：
 * - 开始映射：进入 [com.keymapper.ui.mapping.MappingOverlayActivity]
 * - 加载配置 ▾：弹出配置列表快速切换
 * - 显示/隐藏标记：单击切换标记层可见性；**长按**进入标记管理态（可长按标记删除/重绑）
 * - ⚙ 设置：进入权限引导与设置页
 */
class ControlPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ControlPanelBinding =
        ControlPanelBinding.inflate(LayoutInflater.from(context), this)

    var onStartMapping: (() -> Unit)? = null
    var onLoadConfig: (() -> Unit)? = null
    var onToggleMarkers: (() -> Unit)? = null
    var onToggleMarkerManage: (() -> Unit)? = null
    var onOpenClicker: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_control_panel)
        val padding = (resources.displayMetrics.density * 12).toInt()
        setPadding(padding, padding, padding, padding)

        binding.btnStartMapping.setOnClickListener { onStartMapping?.invoke() }
        binding.btnLoadConfig.setOnClickListener { onLoadConfig?.invoke() }
        binding.btnMarkers.setOnClickListener { onToggleMarkers?.invoke() }
        binding.btnMarkers.setOnLongClickListener {
            onToggleMarkerManage?.invoke()
            true
        }
        binding.btnClicker.setOnClickListener { onOpenClicker?.invoke() }
        binding.btnSettings.setOnClickListener { onOpenSettings?.invoke() }
        binding.btnClose.setOnClickListener { onClose?.invoke() }
    }

    /**
     * 刷新「当前配置」文案（P1-1 切换后即时反馈）。
     */
    fun setActiveConfig(name: String?) {
        binding.tvActiveConfig.text = if (name.isNullOrBlank()) {
            context.getString(R.string.status_no_active_config)
        } else {
            context.getString(R.string.status_active_config, name)
        }
    }

    /**
     * 同步「隐藏/显示绑定点」按钮文案（随当前标记层可见性切换）。
     * 当前标记可见时提示「隐藏绑定点」，已隐藏时提示「显示绑定点」。
     */
    fun setMarkerVisibleState(visible: Boolean) {
        binding.btnMarkers.text = context.getString(
            if (visible) R.string.floating_btn_hide_markers else R.string.floating_btn_show_markers,
        )
    }
}
