package com.keymapper

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.keymapper.data.ConfigRepository
import com.keymapper.data.JsonConfigStore
import com.keymapper.domain.model.KeyBinding
import com.keymapper.domain.model.MappingConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 配置仓库集成测试（P0-5 / P1-6）。
 *
 * 直接构造 [ConfigRepository] + [JsonConfigStore]（不依赖 Koin），在真机 / 模拟器上验证
 * 保存 / 加载 / 删除 / 列表 / 导入导出 的真实文件 IO。
 */
class ConfigRepositoryInstrumentedTest {

    private lateinit var repository: ConfigRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        repository = ConfigRepository(JsonConfigStore(context), prefs)
    }

    @Test
    fun save_and_load() = runBlocking {
        val cfg = MappingConfig(
            configName = "T1",
            screenWidth = 1080,
            screenHeight = 1920,
            bindings = listOf(KeyBinding(x = 1, y = 2, keyCode = 3)),
            createdAt = 1L,
        )
        repository.saveConfig(cfg)
        val loaded = repository.loadConfig("T1")
        assertNotNull(loaded)
        assertEquals(cfg, loaded)
    }

    @Test
    fun delete_removes() = runBlocking {
        repository.saveConfig(MappingConfig(configName = "T2", bindings = emptyList()))
        assertTrue(repository.exists("T2"))
        assertTrue(repository.deleteConfig("T2"))
        assertNull(repository.loadConfig("T2"))
    }

    @Test
    fun list_configs() = runBlocking {
        repository.saveConfig(MappingConfig(configName = "A"))
        repository.saveConfig(MappingConfig(configName = "B"))
        val names = repository.listConfigs()
        assertTrue(names.contains("A"))
        assertTrue(names.contains("B"))
    }

    @Test
    fun export_and_import() = runBlocking {
        val cfg = MappingConfig(
            configName = "Exp",
            screenWidth = 1080,
            screenHeight = 1920,
            bindings = listOf(KeyBinding(x = 5, y = 6, keyCode = 7)),
        )
        repository.saveConfig(cfg)
        val file = File(context.cacheDir, "export_test.json")
        val uri: Uri = Uri.fromFile(file)
        repository.exportToFile(cfg, uri)
        assertTrue(file.exists())
        val imported = repository.importFromFile(uri)
        assertEquals(cfg, imported)
    }
}
