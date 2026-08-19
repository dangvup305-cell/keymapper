package com.keymapper.di

import android.content.Context
import android.content.SharedPreferences
import com.keymapper.data.ConfigRepository
import com.keymapper.data.JsonConfigStore
import com.keymapper.engine.AutoClickerPlayer
import com.keymapper.engine.MacroPlayer
import com.keymapper.engine.MappingEngine
import com.keymapper.util.Constants
import com.keymapper.util.ResolutionScaler
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin 依赖声明。
 *
 * 全部使用 `single`（进程内单例），原因：
 * - [MappingEngine]：绑定表 + 组合键状态机必须全进程唯一，
 *   否则「无障碍服务收到按键」与「配置面板载入的绑定」会落在两个实例上，导致按键无响应；
 * - [MacroPlayer]：持有回放协程，需要能被引擎取消上一次回放；
 * - [ConfigRepository] / [JsonConfigStore] / [SharedPreferences]：无状态或轻状态，共享即可；
 * - [ResolutionScaler]：纯函数工具，单例避免重复创建。
 *
 * 被注入方一览（必须与此处声明一一对应）：
 * | 类 | 注入内容 |
 * | --- | --- |
 * | [com.keymapper.accessibility.KeyMapperAccessibilityService] | MappingEngine / ConfigRepository / ResolutionScaler |
 * | [com.keymapper.service.KeepAliveService] | ConfigRepository |
 * | [com.keymapper.service.FloatingWindowService] | MappingEngine / ConfigRepository |
 * | [com.keymapper.MainActivity] | MappingEngine / ConfigRepository |
 * | [com.keymapper.ui.mapping.MappingOverlayActivity] | MappingEngine / ConfigRepository |
 * | [com.keymapper.ui.config.ConfigPanelActivity] | MappingEngine / ConfigRepository |
 * | [com.keymapper.ui.settings.SettingsActivity] | ConfigRepository |
 */
val appModule = module {

    single<SharedPreferences> {
        androidContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    single { JsonConfigStore(androidContext()) }

    single { ConfigRepository(store = get(), prefs = get()) }

    single { ResolutionScaler() }

    single { MacroPlayer() }

    /** 循环连点器：进程内单例，供无障碍服务/悬浮窗/连点器页面共享同一运行状态。 */
    single { AutoClickerPlayer() }

    single { MappingEngine(scaler = get(), macroPlayer = get()) }
}
