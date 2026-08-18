package com.keymapper

import com.keymapper.domain.model.ActionType
import com.keymapper.domain.model.KeyBinding
import com.keymapper.domain.model.KeyMapperJson
import com.keymapper.domain.model.MappingConfig
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JSON 序列化 / 反序列化与兼容字段单元测试（P0-7）。
 *
 * 验证：
 * - [KeyBinding] 序列化后反序列化保持相等
 * - 未知字段被忽略（`ignoreUnknownKeys = true`，保证旧版本文件向前兼容）
 * - 缺失字段走 data class 默认值（actionType 默认 CLICK、keyCombination 默认 null）
 * - [MappingConfig] 含绑定列表时整体往返一致
 */
class KeyBindingSerializationTest {

    @Test
    fun keyBinding_roundTrip() {
        val b = KeyBinding(
            x = 540,
            y = 960,
            keyCode = 33,
            labelColor = "#FF5722",
            actionType = ActionType.CLICK,
            label = "技能1",
        )
        val json = KeyMapperJson.instance.encodeToString(b)
        val back = KeyMapperJson.instance.decodeFromString<KeyBinding>(json)
        assertEquals(b, back)
    }

    @Test
    fun keyBinding_unknownFieldsIgnored() {
        val json = """{"x":10,"y":20,"keyCode":30,"extraUnknown":"ignored","labelColor":"#2196F3"}"""
        val b = KeyMapperJson.instance.decodeFromString<KeyBinding>(json)
        assertEquals(30, b.keyCode)
        assertEquals("#2196F3", b.labelColor)
        assertEquals(ActionType.CLICK, b.actionType)
        assertNull(b.keyCombination)
    }

    @Test
    fun keyBinding_actionType_defaultsToClick() {
        val json = """{"x":1,"y":2,"keyCode":3}"""
        val b = KeyMapperJson.instance.decodeFromString<KeyBinding>(json)
        assertEquals(ActionType.CLICK, b.actionType)
    }

    @Test
    fun mappingConfig_roundTrip_withBindings() {
        val cfg = MappingConfig(
            configName = "测试方案",
            screenWidth = 1080,
            screenHeight = 1920,
            bindings = listOf(
                KeyBinding(x = 1, y = 2, keyCode = 3, actionType = ActionType.LONG_PRESS),
            ),
            createdAt = 123L,
            appPackage = "com.test",
        )
        val json = cfg.toJson()
        val back = MappingConfig.fromJson(json)
        assertEquals(cfg, back)
        assertEquals(ActionType.LONG_PRESS, back.bindings.first().actionType)
    }
}
