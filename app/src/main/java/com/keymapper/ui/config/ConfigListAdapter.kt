package com.keymapper.ui.config

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.keymapper.R
import com.keymapper.databinding.ItemConfigBinding
import com.keymapper.domain.model.MappingConfig

/**
 * 配置列表适配器（P0-5 / P1-1）。
 *
 * 每行展示配置名 + 摘要（绑定数 · 源分辨率），并提供「载入」「删除」操作；
 * 单选圆点反映当前生效配置。整行点击选中（供导出使用）。
 */
class ConfigListAdapter(
    private val onLoad: (MappingConfig) -> Unit,
    private val onDelete: (MappingConfig) -> Unit,
) : RecyclerView.Adapter<ConfigListAdapter.ViewHolder>() {

    private val items = mutableListOf<MappingConfig>()
    private var activeName: String? = null

    fun update(newItems: List<MappingConfig>, newActive: String?) {
        items.clear()
        items.addAll(newItems)
        activeName = newActive
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(val binding: ItemConfigBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cfg: MappingConfig) {
            val context = binding.root.context
            binding.tvName.text = cfg.configName
            binding.tvSummary.text = context.getString(
                R.string.config_item_summary,
                cfg.bindingCount,
                cfg.screenWidth,
                cfg.screenHeight,
            )
            binding.radioSelected.isChecked = cfg.configName == activeName
            binding.root.setOnClickListener { onLoad(cfg) }
            binding.btnLoad.setOnClickListener { onLoad(cfg) }
            binding.btnDelete.setOnClickListener { onDelete(cfg) }
        }
    }
}
