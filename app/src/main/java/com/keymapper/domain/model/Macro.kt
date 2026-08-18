package com.keymapper.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 宏指令（P1-3）：一串按时序执行的 [MacroStep]。
 *
 * 例："按下 W → 延迟 500ms → 点击屏幕 → 松开 W" 对应 4 个步骤。
 */
@Serializable
data class Macro(
    val name: String = "",
    val steps: List<MacroStep> = emptyList(),
) {

    /** 是否为空宏（无任何步骤时引擎不会回放）。 */
    val isEmpty: Boolean get() = steps.isEmpty()

    /** 序列化为 JSON 字符串（与配置文件使用同一 Json 实例，格式一致）。 */
    fun toJson(): String = KeyMapperJson.instance.encodeToString(this)

    /** 预估整个宏的回放总时长，供 UI 展示。 */
    fun totalDurationMs(): Long = steps.sumOf { it.totalDurationMs() }

    /** 按比例缩放全部步骤坐标（P1-4）。 */
    fun scaled(scaleX: Double, scaleY: Double): Macro =
        copy(steps = steps.map { it.scaled(scaleX, scaleY) })

    companion object {

        /**
         * 从 JSON 字符串解析宏。
         *
         * @throws IllegalArgumentException JSON 非法时抛出，附带原始异常信息
         */
        fun fromJson(raw: String): Macro = try {
            KeyMapperJson.instance.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("宏 JSON 解析失败：${e.message}", e)
        }
    }
}
