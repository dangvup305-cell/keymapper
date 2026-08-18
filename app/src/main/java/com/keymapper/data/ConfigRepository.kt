package com.keymapper.data

import android.content.SharedPreferences
import android.net.Uri
import com.keymapper.domain.model.MappingConfig
import com.keymapper.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 配置用例封装（P0-5 / P1-1 / P1-6）。
 *
 * 职责：
 * - 把 [JsonConfigStore] 的同步 IO 统一切到 `Dispatchers.IO`
 * - 维护「当前生效配置名」（存 SharedPreferences，供悬浮窗/引擎重启后恢复）
 * - 提供 P1-1 按前台应用包名查找配置的能力
 *
 * 所有方法都可能抛出 [ConfigStoreException]，调用方负责提示用户。
 */
class ConfigRepository(
    private val store: JsonConfigStore,
    private val prefs: SharedPreferences,
) {

    /** 保存（覆盖同名）配置。 */
    suspend fun saveConfig(cfg: MappingConfig) = withContext(Dispatchers.IO) {
        store.write(cfg.sanitized())
    }

    /** 载入配置，不存在返回 null。 */
    suspend fun loadConfig(name: String): MappingConfig? = withContext(Dispatchers.IO) {
        store.read(name)
    }

    /** 删除配置；若删除的是当前生效配置，同时清空生效标记。 */
    suspend fun deleteConfig(name: String): Boolean = withContext(Dispatchers.IO) {
        val deleted = store.delete(name)
        if (deleted && activeConfigName() == name) {
            clearActiveConfigName()
        }
        deleted
    }

    /** 列出全部配置名。 */
    suspend fun listConfigs(): List<String> = withContext(Dispatchers.IO) {
        store.listNames()
    }

    /** 读取全部配置对象。 */
    suspend fun listAllConfigs(): List<MappingConfig> = withContext(Dispatchers.IO) {
        store.readAll()
    }

    /** 配置是否已存在（导入冲突判定用）。 */
    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        store.exists(name)
    }

    /** 导出到 SAF Uri（P1-6）。 */
    suspend fun exportToFile(cfg: MappingConfig, uri: Uri) = withContext(Dispatchers.IO) {
        store.writeToUri(cfg, uri)
    }

    /**
     * 从 SAF Uri 导入（P1-6）。只解析并返回，不落盘；
     * 由调用方处理同名冲突（覆盖/重命名）后再调用 [saveConfig]。
     */
    suspend fun importFromFile(uri: Uri): MappingConfig = withContext(Dispatchers.IO) {
        store.readFromUri(uri)
    }

    /**
     * 生成一个不冲突的配置名，如「原神方案」已存在则返回「原神方案 (2)」。
     */
    suspend fun suggestAvailableName(base: String): String = withContext(Dispatchers.IO) {
        val trimmed = base.ifBlank { "未命名配置" }
        if (!store.exists(trimmed)) return@withContext trimmed
        var index = 2
        while (index < 1000) {
            val candidate = "$trimmed ($index)"
            if (!store.exists(candidate)) return@withContext candidate
            index++
        }
        "$trimmed (${System.currentTimeMillis()})"
    }

    /**
     * P1-1：按前台应用包名查找绑定了该包名的配置。
     *
     * @return 命中的配置；无匹配返回 null
     */
    suspend fun findConfigForPackage(packageName: String): MappingConfig? =
        withContext(Dispatchers.IO) {
            if (packageName.isBlank()) return@withContext null
            store.readAll().firstOrNull { it.appPackage == packageName }
        }

    // ------------------------------------------------------------------
    // 当前生效配置（SharedPreferences，同步读写、开销极小）
    // ------------------------------------------------------------------

    /** 当前生效配置名，未选择返回 null。 */
    fun activeConfigName(): String? =
        prefs.getString(Constants.PREF_ACTIVE_CONFIG, null)?.takeIf { it.isNotBlank() }

    /** 设置当前生效配置名。 */
    fun setActiveConfigName(name: String) {
        prefs.edit().putString(Constants.PREF_ACTIVE_CONFIG, name).apply()
    }

    /** 清空当前生效配置名。 */
    fun clearActiveConfigName() {
        prefs.edit().remove(Constants.PREF_ACTIVE_CONFIG).apply()
    }

    /** 载入当前生效配置（供服务启动时恢复现场）。 */
    suspend fun loadActiveConfig(): MappingConfig? {
        val name = activeConfigName() ?: return null
        return loadConfig(name)
    }

    // ------------------------------------------------------------------
    // 其它开关（供设置页与服务共享）
    // ------------------------------------------------------------------

    /** P1-1 是否按前台应用自动切换配置。 */
    fun isAutoSwitchEnabled(): Boolean =
        prefs.getBoolean(Constants.PREF_AUTO_SWITCH_BY_PACKAGE, false)

    fun setAutoSwitchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_AUTO_SWITCH_BY_PACKAGE, enabled).apply()
    }

    /** P2-3 是否开启低电量降频。 */
    fun isLowBatterySaverEnabled(): Boolean =
        prefs.getBoolean(Constants.PREF_LOW_BATTERY_SAVER, true)

    fun setLowBatterySaverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_LOW_BATTERY_SAVER, enabled).apply()
    }

    /** P0-4 标记层是否可见。 */
    fun isMarkersVisible(): Boolean = prefs.getBoolean(Constants.PREF_MARKERS_VISIBLE, true)

    fun setMarkersVisible(visible: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_MARKERS_VISIBLE, visible).apply()
    }

    /** P0-8 是否已完成首次权限引导。 */
    fun isFirstLaunchDone(): Boolean = prefs.getBoolean(Constants.PREF_FIRST_LAUNCH_DONE, false)

    fun markFirstLaunchDone() {
        prefs.edit().putBoolean(Constants.PREF_FIRST_LAUNCH_DONE, true).apply()
    }
}
