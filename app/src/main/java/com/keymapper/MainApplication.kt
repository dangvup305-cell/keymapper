package com.keymapper

import android.app.Application
import android.util.Log
import com.keymapper.data.JsonConfigStore
import com.keymapper.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * 应用入口：初始化 Koin 容器并预热配置目录。
 *
 * 之所以用 Koin 而不是手写单例：
 * [com.keymapper.engine.MappingEngine] 需要被 Activity（映射/配置面板）、
 * 无障碍服务、悬浮窗服务**同一实例**共享，用容器统一托管比静态字段更清晰、可测。
 *
 * 注意：本类在 `AndroidManifest.xml` 中通过 `android:name=".MainApplication"` 声明，
 * 无障碍服务与前台服务都可能先于 Activity 创建，因此依赖注册必须在此完成。
 */
class MainApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val koinApplication = startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }

        // 预热配置目录（filesDir/configs），避免首次保存时才创建目录导致的 IO 抖动
        try {
            koinApplication.koin.get<JsonConfigStore>().configDir()
        } catch (e: Exception) {
            Log.w(TAG, "预热配置目录失败：${e.message}")
        }

        Log.i(TAG, "KeyMapper 已启动，依赖容器初始化完成")
    }

    private companion object {
        const val TAG = "KeyMapperApp"
    }
}
