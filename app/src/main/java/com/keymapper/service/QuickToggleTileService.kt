package com.keymapper.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.keymapper.R
import com.keymapper.util.PermissionHelper

/**
 * 快捷磁贴：一键开关悬浮窗（悬浮球 + 操作面板 + 标记层）。
 *
 * - 未授权悬浮窗权限时跳转授权页；
 * - 磁贴状态实时反映悬浮窗服务运行与否（ACTIVE = 运行中）。
 */
class QuickToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingWindowService.isRunning) {
            FloatingWindowService.stop(this)
        } else {
            if (!PermissionHelper.canDrawOverlays(this)) {
                PermissionHelper.openOverlaySettings(this)
                return
            }
            FloatingWindowService.start(this)
        }
        // 服务启停是异步的，稍作延时再刷新磁贴状态
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateTile() }, TILE_REFRESH_DELAY_MS)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        try {
            tile.state = if (FloatingWindowService.isRunning) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            tile.label = getString(R.string.tile_label)
            tile.updateTile()
        } catch (e: Exception) {
            Log.w(TAG, "更新磁贴状态失败：${e.message}")
        }
    }

    companion object {
        private const val TAG = "KeyMapperTile"

        /** 服务启停异步完成的刷新延时。 */
        private const val TILE_REFRESH_DELAY_MS = 300L
    }
}
