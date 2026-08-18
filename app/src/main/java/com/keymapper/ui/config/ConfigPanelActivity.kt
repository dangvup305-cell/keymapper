package com.keymapper.ui.config

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.keymapper.R
import com.keymapper.data.ConfigRepository
import com.keymapper.databinding.ActivityConfigPanelBinding
import com.keymapper.domain.model.MappingConfig
import com.keymapper.engine.MappingEngine
import com.keymapper.service.FloatingWindowService
import com.keymapper.ui.mapping.MappingOverlayActivity
import com.keymapper.util.Constants
import com.keymapper.util.PermissionHelper
import com.keymapper.util.ResolutionScaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * 配置管理面板（P0-5 / P1-1 / P1-6 / P1-5）。
 *
 * 能力：
 * - 命名保存后的配置列表（由 [MappingOverlayActivity] 保存产生）
 * - 点击/「载入」：设为当前生效配置并广播 [Constants.ACTION_SWITCH_CONFIG] 让悬浮窗服务刷新引擎与标记
 * - 「删除」二次确认
 * - 导出 / 导入（SAF）
 * - 「测试映射」：开关测试模式（P1-5），经由广播让悬浮窗服务显示高亮层
 */
class ConfigPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigPanelBinding
    private val repository: ConfigRepository by inject()
    private val engine: MappingEngine by inject()

    private lateinit var adapter: ConfigListAdapter
    private var selectedConfig: MappingConfig? = null
    private var testModeOn = false

    private val createDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(Constants.CONFIG_MIME_TYPE),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val cfg = selectedConfig ?: run {
            toast(R.string.config_toast_select_first)
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                repository.exportToFile(cfg, uri)
                toast(R.string.config_toast_exported)
            } catch (e: Exception) {
                toast(getString(R.string.config_toast_export_failed, e.message ?: ""))
            }
        }
    }

    private val openDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val cfg = repository.importFromFile(uri)
                onImported(cfg)
            } catch (e: Exception) {
                toast(getString(R.string.config_toast_import_failed, e.message ?: ""))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ConfigListAdapter(
            onLoad = { loadConfig(it) },
            onDelete = { confirmDelete(it) },
        )
        binding.rvConfigs.layoutManager = LinearLayoutManager(this)
        binding.rvConfigs.adapter = adapter

        binding.btnNew.setOnClickListener {
            startActivity(Intent(this, MappingOverlayActivity::class.java))
        }
        binding.btnExport.setOnClickListener {
            val cfg = selectedConfig
            if (cfg == null) {
                toast(R.string.config_toast_select_first)
            } else {
                createDoc.launch(cfg.configName + Constants.CONFIG_FILE_EXTENSION)
            }
        }
        binding.btnImport.setOnClickListener {
            openDoc.launch(arrayOf(Constants.CONFIG_MIME_TYPE))
        }
        binding.btnTest.setOnClickListener { toggleTestMode() }

        // config_scaled_hint 带两个占位符（宽×高），必须传入实际分辨率，否则会显示原始格式串
        val (screenWidth, screenHeight) = ResolutionScaler.realScreenSize(this)
        binding.tvHint.text = getString(R.string.config_scaled_hint, screenWidth, screenHeight)
        engine.setTargetResolution(screenWidth, screenHeight)
    }

    override fun onResume() {
        super.onResume()
        loadList()
    }

    // ------------------------------------------------------------------
    // 列表 / 载入 / 删除
    // ------------------------------------------------------------------

    private fun loadList() {
        lifecycleScope.launch {
            val cfgs = repository.listAllConfigs()
            val active = repository.activeConfigName()
            if (selectedConfig == null || cfgs.none { it.configName == selectedConfig?.configName }) {
                selectedConfig = cfgs.firstOrNull { it.configName == active }
            }
            withContext(Dispatchers.Main) {
                adapter.update(cfgs, active)
                binding.tvEmpty.visibility = if (cfgs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadConfig(cfg: MappingConfig) {
        selectedConfig = cfg
        lifecycleScope.launch {
            repository.setActiveConfigName(cfg.configName)
            sendBroadcast(
                Intent(Constants.ACTION_SWITCH_CONFIG)
                    .putExtra(Constants.EXTRA_KEYMAPPER_CONFIG_NAME, cfg.configName)
                    .setPackage(packageName),
            )
            withContext(Dispatchers.Main) {
                toast(getString(R.string.config_toast_loaded, cfg.configName))
                loadList()
            }
        }
    }

    private fun confirmDelete(cfg: MappingConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.config_delete_confirm_title)
            .setMessage(getString(R.string.config_delete_confirm_msg, cfg.configName))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteConfig(cfg.configName)
                    if (selectedConfig?.configName == cfg.configName) selectedConfig = null
                    sendBroadcast(Intent(Constants.ACTION_CONFIG_CHANGED).setPackage(packageName))
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.config_toast_deleted, cfg.configName))
                        loadList()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // 导入 / 冲突处理
    // ------------------------------------------------------------------

    private fun onImported(cfg: MappingConfig) {
        lifecycleScope.launch {
            if (repository.exists(cfg.configName)) {
                withContext(Dispatchers.Main) { showConflictDialog(cfg) }
            } else {
                repository.saveConfig(cfg)
                repository.setActiveConfigName(cfg.configName)
                sendBroadcast(
                    Intent(Constants.ACTION_SWITCH_CONFIG)
                        .putExtra(Constants.EXTRA_KEYMAPPER_CONFIG_NAME, cfg.configName)
                        .setPackage(packageName),
                )
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.config_toast_imported, cfg.configName))
                    loadList()
                }
            }
        }
    }

    private fun showConflictDialog(cfg: MappingConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.config_import_conflict_title)
            .setMessage(getString(R.string.config_import_conflict_msg, cfg.configName))
            .setPositiveButton(R.string.overwrite) { _, _ ->
                lifecycleScope.launch {
                    repository.saveConfig(cfg)
                    repository.setActiveConfigName(cfg.configName)
                    sendBroadcast(
                        Intent(Constants.ACTION_SWITCH_CONFIG)
                            .putExtra(Constants.EXTRA_KEYMAPPER_CONFIG_NAME, cfg.configName)
                            .setPackage(packageName),
                    )
                    withContext(Dispatchers.Main) { loadList() }
                }
            }
            .setNegativeButton(R.string.rename) { _, _ ->
                lifecycleScope.launch {
                    val newName = repository.suggestAvailableName(cfg.configName)
                    val renamed = cfg.copy(configName = newName)
                    repository.saveConfig(renamed)
                    repository.setActiveConfigName(newName)
                    sendBroadcast(
                        Intent(Constants.ACTION_SWITCH_CONFIG)
                            .putExtra(Constants.EXTRA_KEYMAPPER_CONFIG_NAME, newName)
                            .setPackage(packageName),
                    )
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.config_toast_imported, newName))
                        loadList()
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------
    // 测试模式（P1-5）
    // ------------------------------------------------------------------

    private fun toggleTestMode() {
        testModeOn = !testModeOn
        if (testModeOn && !PermissionHelper.isAccessibilityServiceRunning()) {
            toast(R.string.error_accessibility_not_running)
            testModeOn = false
            return
        }
        engine.setTestMode(testModeOn)
        if (testModeOn) {
            if (!FloatingWindowService.isRunning) FloatingWindowService.start(this)
            sendBroadcast(Intent(Constants.ACTION_TEST_MODE_ON).setPackage(packageName))
            binding.btnTest.setText(R.string.config_btn_test_exit)
            toast(R.string.config_test_mode_on)
        } else {
            sendBroadcast(Intent(Constants.ACTION_TEST_MODE_OFF).setPackage(packageName))
            binding.btnTest.setText(R.string.config_btn_test)
            toast(R.string.config_test_mode_off)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
