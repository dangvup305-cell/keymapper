package com.keymapper.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 循环连点器配置（模式二·屏幕辅助）。
 *
 * 用户选定一个或多个坐标后，按 [intervalMs] 循环注入点击；
 * [runCount] > 0 时点击次数达到即停，[durationMs] > 0 时运行时长达到即停，均可单独设 0 表示不限。
 *
 * 与按键映射完全独立：运行期间用户仍可正常触屏操作（离散点击互不干扰）。
 */
@Serializable
data class AutoClickerConfig(
    val points: List<Point> = emptyList(),
    val intervalMs: Long = 300L,
    val runCount: Int = 0,
    val durationMs: Long = 0L,
) {

    /** 是否有点位可运行。 */
    val hasPoints: Boolean get() = points.isNotEmpty()

    /** 归一化间隔：不小于 50ms，避免过密导致系统丢弃手势。 */
    fun safeInterval(): Long = if (intervalMs < 50L) 50L else intervalMs

    /** 序列化为 JSON 字符串（与配置同一 Json 实例）。 */
    fun toJson(): String = KeyMapperJson.instance.encodeToString(this)

    companion object {

        /** 从 JSON 解析，非法时回退为空配置。 */
        fun fromJson(raw: String): AutoClickerConfig = try {
            KeyMapperJson.instance.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            AutoClickerConfig()
        }
    }
}
