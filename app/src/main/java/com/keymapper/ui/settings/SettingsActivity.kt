package com.keymapper.ui.settings

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.keymapper.R
import com.keymapper.data.ConfigRepository
import com.keymapper.databinding.ActivitySettingsBinding
import com.keymapper.ui.config.ConfigPanelActivity
import com.keymapper.util.KeyEventUtil
import com.keymapper.util.PermissionHelper
import org.koin.android.ext.android.inject

/**
 * 设置与权限引导页（P0-8 / P2-3）。
 *
 * 逐项检查并引导授权：
 * - 无障碍服务（核心，键盘监听 + 手势注入）
 * - 悬浮窗（SYSTEM_ALERT_WINDOW）
 * - 通知权限（Android 13+ 前台保活通知）
 *
 * 并提供：低电量降频开关、按前台应用自动切换开关、鼠标滚轮能力提示、入口到配置面板。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repository: ConfigRepository by inject()

    /** Android 13+ 通知权限请求器（设置页内可直接申请，比单纯跳设置页更顺手）。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccessibility.setOnClickListener { PermissionHelper.openAccessibilitySettings(this) }
        binding.btnWhyAccessibility.setOnClickListener { showWhy(R.string.perm_accessibility_desc) }
        binding.btnOverlay.setOnClickListener { PermissionHelper.openOverlaySettings(this) }
        binding.btnWhyOverlay.setOnClickListener { showWhy(R.string.perm_overlay_desc) }
        binding.btnNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !PermissionHelper.hasNotificationPermission(this)
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionHelper.openNotificationSettings(this)
            }
        }
        binding.btnWhyNotification.setOnClickListener { showWhy(R.string.perm_notification_desc) }
        binding.btnBatteryOpt.setOnClickListener {
            PermissionHelper.openBatteryOptimizationSettings(this)
        }
        binding.btnWhyBattery.setOnClickListener { showWhy(R.string.perm_battery_desc) }
        binding.btnOpenConfigPanel.setOnClickListener {
            startActivity(android.content.Intent(this, ConfigPanelActivity::class.java))
        }

        binding.switchLowBattery.setOnCheckedChangeListener { _, checked ->
            repository.setLowBatterySaverEnabled(checked)
        }
        binding.switchAutoSwitch.setOnCheckedChangeListener { _, checked ->
            repository.setAutoSwitchEnabled(checked)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** 根据实时权限状态刷新文案与开关。 */
    private fun refreshStatus() {
        val accessibilityOn = PermissionHelper.isAccessibilityEnabled(this)
        val overlayOn = PermissionHelper.canDrawOverlays(this)
        val notificationOn = PermissionHelper.hasNotificationPermission(this)

        setStatus(binding.tvAccessibilityStatus, accessibilityOn)
        setStatus(binding.tvOverlayStatus, overlayOn)
        setStatus(binding.tvNotificationStatus, notificationOn)
        setBatteryStatus(binding.tvBatteryOptStatus, PermissionHelper.isBatteryOptimized(this))

        binding.switchLowBattery.isChecked = repository.isLowBatterySaverEnabled()
        binding.switchAutoSwitch.isChecked = repository.isAutoSwitchEnabled()

        // P2-2 无鼠标设备时优雅隐藏滚轮能力提示
        if (KeyEventUtil.hasMouseDevice()) {
            binding.tvMouseWheelStatus.visibility = android.view.View.GONE
        } else {
            binding.tvMouseWheelStatus.setText(R.string.error_no_mouse_device)
            binding.tvMouseWheelStatus.visibility = android.view.View.VISIBLE
        }

        // 首次进入完成引导标记
        if (accessibilityOn && overlayOn) {
            repository.markFirstLaunchDone()
        }
    }

    private fun setStatus(textView: android.widget.TextView, granted: Boolean) {
        textView.setText(if (granted) R.string.granted else R.string.not_granted)
        textView.setTextColor(
            getColor(
                if (granted) R.color.status_ok else R.color.status_error,
            ),
        )
    }

    private fun setBatteryStatus(textView: android.widget.TextView, optimized: Boolean) {
        textView.setText(
            if (optimized) R.string.perm_battery_status_optimized
            else R.string.perm_battery_status_unoptimized,
        )
        textView.setTextColor(getColor(if (optimized) R.color.status_error else R.color.status_ok))
    }

    private fun showWhy(descRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.why_needed)
            .setMessage(descRes)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
