package com.keymapper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.keymapper.util.PermissionHelper

/**
 * 开机 / 应用更新后自启保活服务。
 *
 * 针对一加 / ColorOS 等后台管理激进的 ROM：这些系统开机后不会自动拉起
 * 无障碍服务与前台保活服务，导致用户重启手机后按键映射「无声失效」。
 * 本接收器在以下时机拉起 [KeepAliveService]：
 * - [Intent.ACTION_BOOT_COMPLETED]：正常开机完成
 * - [Intent.ACTION_LOCKED_BOOT_COMPLETED]：解锁后完成（部分 ROM 时序）
 * - [Intent.ACTION_MY_PACKAGE_REPLACED]：本应用更新安装完成
 *
 * 仅当无障碍服务仍处于开启状态时才拉起，避免用户未启用时开机即常驻通知骚扰。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (!PermissionHelper.isAccessibilityEnabled(context)) return
                Log.i(TAG, "系统广播（${intent.action}），重启保活服务")
                KeepAliveService.start(context)
            }
        }
    }

    private companion object {
        const val TAG = "KeyMapperBoot"
    }
}
