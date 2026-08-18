package com.keymapper

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.keymapper.data.ConfigRepository
import com.keymapper.databinding.ActivityMainBinding
import com.keymapper.engine.MappingEngine
import com.keymapper.service.FloatingWindowService
import com.keymapper.ui.config.ConfigPanelActivity
import com.keymapper.ui.mapping.MappingOverlayActivity
import com.keymapper.ui.settings.SettingsActivity
import com.keymapper.util.Constants
import com.keymapper.util.PermissionHelper
import com.keymapper.util.ResolutionScaler
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * 入口 Activity（P0-1 / P0-8）。
 *
 * 职责：
 * 1. 展示权限就绪状态与当前生效配置；
 * 2. 一键启动/关闭悬浮窗（P0-1）；
 * 3. 直达映射模式、配置面板、权限引导页；
 * 4. 首次启动自动跳转权限引导（P0-8）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val repository: ConfigRepository by inject()
    private val engine: MappingEngine by inject()

    /** Android 13+ 通知权限请求器（前台保活通知需要）。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i(TAG, "通知权限授予结果：$granted")
        refreshStatus()
    }

    /** 通知权限只在一次会话内请求一次，避免反复弹窗骚扰。 */
    private var notificationPermissionAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggleFloating.setOnClickListener { toggleFloatingWindow() }
        binding.btnStartMapping.setOnClickListener { startMappingMode() }
        binding.btnConfigPanel.setOnClickListener {
            startActivity(Intent(this, ConfigPanelActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 同步当前屏幕分辨率给引擎，保证 P1-4 缩放基准可用
        val (width, height) = ResolutionScaler.realScreenSize(this)
        engine.setTargetResolution(width, height)

        guideFirstLaunch()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        restoreActiveConfigIfNeeded()
        requestNotificationPermissionIfNeeded()
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    /** 刷新权限提示、生效配置与悬浮窗按钮文案。 */
    private fun refreshStatus() {
        val missing = PermissionHelper.missingPermissions(this)
        binding.tvPermissionStatus.text = if (missing.isEmpty()) {
            getString(R.string.status_permission_ready)
        } else {
            getString(R.string.status_permission_missing) + "：" + missing.joinToString("、")
        }

        val activeName = repository.activeConfigName()
        binding.tvActiveConfig.text = if (activeName.isNullOrBlank()) {
            getString(R.string.status_no_active_config)
        } else {
            getString(R.string.status_active_config, activeName)
        }

        binding.btnToggleFloating.text = if (FloatingWindowService.isRunning) {
            getString(R.string.btn_stop_floating)
        } else {
            getString(R.string.btn_start_floating)
        }
    }

    /** 引擎为空时补载一次当前生效配置（例如无障碍服务尚未连接）。 */
    private fun restoreActiveConfigIfNeeded() {
        if (engine.currentConfig != null) return
        lifecycleScope.launch {
            try {
                val active = repository.loadActiveConfig() ?: return@launch
                engine.loadBindings(active)
                sendLocalBroadcast(Constants.ACTION_BINDINGS_UPDATED)
            } catch (e: Exception) {
                Log.w(TAG, "载入生效配置失败：${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /** 启动/关闭悬浮窗（P0-1）。 */
    private fun toggleFloatingWindow() {
        if (FloatingWindowService.isRunning) {
            FloatingWindowService.stop(this)
            toast(getString(R.string.toast_floating_stopped))
            binding.btnToggleFloating.postDelayed({ refreshStatus() }, STATUS_REFRESH_DELAY_MS)
            return
        }

        if (!PermissionHelper.canDrawOverlays(this)) {
            toast(getString(R.string.toast_need_permission))
            PermissionHelper.openOverlaySettings(this)
            return
        }

        FloatingWindowService.start(this)
        toast(getString(R.string.toast_floating_started))
        binding.btnToggleFloating.postDelayed({ refreshStatus() }, STATUS_REFRESH_DELAY_MS)
    }

    /**
     * 进入映射模式（P0-2）。
     *
     * 悬浮窗服务在运行时改用广播，让服务先收起悬浮球再拉起覆盖层，避免小球遮挡采点。
     */
    private fun startMappingMode() {
        if (!PermissionHelper.allRequiredGranted(this)) {
            toast(getString(R.string.toast_need_permission))
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (FloatingWindowService.isRunning) {
            sendLocalBroadcast(Constants.ACTION_START_MAPPING)
        } else {
            startActivity(Intent(this, MappingOverlayActivity::class.java))
        }
    }

    /** 首次启动引导到权限页（P0-8）。 */
    private fun guideFirstLaunch() {
        if (repository.isFirstLaunchDone()) return
        repository.markFirstLaunchDone()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Android 13+ 主动申请通知权限，失败也不阻塞主流程。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPermissionAsked) return
        if (PermissionHelper.hasNotificationPermission(this)) return
        notificationPermissionAsked = true
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.w(TAG, "申请通知权限失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun sendLocalBroadcast(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "KeyMapperMain"

        /** 服务启停是异步的，稍后刷新一次按钮状态。 */
        const val STATUS_REFRESH_DELAY_MS = 400L
    }
}
