package com.keymapper.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全工程共享的 [Json] 实例。
 *
 * 关键配置：
 * - `ignoreUnknownKeys = true`：老版本 App 读到新增字段不会崩，保证向后兼容
 * - `encodeDefaults = true`：默认值也写进 JSON，导出的文件字段完整、便于人工编辑
 * - `explicitNulls = false`：`gestureParams/macro/appPackage` 等空值不写入，文件更精简；
 *   反序列化时缺失字段会走 data class 默认值（P0-7「字段缺失给合理默认」）
 * - `coerceInputValues = true`：枚举等非法取值退化为默认值而不是直接抛异常
 */
object KeyMapperJson {
    val instance: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }
}

/**
 * 一份命名映射配置（P0-5 / P0-7 / P1-1 / P1-4）。
 *
 * JSON 字段约定：
 * ```json
 * {
 *   "configName": "王者荣耀方案",
 *   "screenWidth": 1080,
 *   "screenHeight": 2400,
 *   "createdAt": 1718700000000,
 *   "appPackage": null,
 *   "bindings": [ ... ]
 * }
 * ```
 *
 * @property screenWidth  保存时的源分辨率宽（px），供 [scaledCopy] 换算
 * @property screenHeight 保存时的源分辨率高（px）
 * @property appPackage   绑定的前台应用包名（P1-1 自动切换用），为 null 表示不自动匹配
 * @property snapToGrid   网格吸附开关：采点/拖动微调时坐标对齐网格，避免键位重叠冲突
 * @property gridSize     网格间距（dp），<=0 时使用 [com.keymapper.util.Constants.DEFAULT_GRID_SIZE_DP]
 */
@Serializable
data class MappingConfig(
    val configName: String = "",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val bindings: List<KeyBinding> = emptyList(),
    val createdAt: Long = 0L,
    val appPackage: String? = null,
    val snapToGrid: Boolean = false,
    val gridSize: Int = 0,
) {

    /** 配置内有效绑定数量。 */
    val bindingCount: Int get() = bindings.size

    /** 源分辨率是否可用（可用才能做等比缩放）。 */
    val hasValidResolution: Boolean get() = screenWidth > 0 && screenHeight > 0

    /** 序列化为 JSON 字符串。 */
    fun toJson(): String = KeyMapperJson.instance.encodeToString(this)

    /**
     * 按目标分辨率等比缩放全部坐标（P1-4）。
     *
     * - 源分辨率无效（0）时无法换算，原样返回（并把目标分辨率补写进去）
     * - 目标分辨率与源分辨率一致时直接返回自身，保证「同分辨率加载坐标无偏移」
     *
     * @param targetW 目标屏幕宽（px），通常来自 `Display.getRealSize()`
     * @param targetH 目标屏幕高（px）
     */
    fun scaledCopy(targetW: Int, targetH: Int): MappingConfig {
        if (targetW <= 0 || targetH <= 0) return this
        if (!hasValidResolution) {
            return copy(screenWidth = targetW, screenHeight = targetH)
        }
        if (screenWidth == targetW && screenHeight == targetH) return this

        val scaleX = targetW.toDouble() / screenWidth.toDouble()
        val scaleY = targetH.toDouble() / screenHeight.toDouble()
        return copy(
            screenWidth = targetW,
            screenHeight = targetH,
            bindings = bindings.map { it.scaled(scaleX, scaleY) },
        )
    }

    /**
     * 只保留有效绑定的副本，用于保存前清理脏数据。
     */
    fun sanitized(): MappingConfig = copy(bindings = bindings.filter { it.isValid() })

    /**
     * 追加或替换一条绑定：同签名（同按键）的旧绑定会被覆盖，保证一个按键只对应一个动作。
     *
     * @return 新配置 + 是否覆盖了旧绑定
     */
    fun upsertBinding(binding: KeyBinding): Pair<MappingConfig, Boolean> {
        val signature = binding.signature()
        val index = bindings.indexOfFirst { it.signature() == signature }
        return if (index >= 0) {
            val mutable = bindings.toMutableList()
            mutable[index] = binding
            copy(bindings = mutable) to true
        } else {
            copy(bindings = bindings + binding) to false
        }
    }

    /** 移除指定签名的绑定。 */
    fun removeBinding(signature: List<Int>): MappingConfig =
        copy(bindings = bindings.filterNot { it.signature() == signature })

    companion object {

        /**
         * 创建一份空配置，`createdAt` 取当前时间。
         */
        fun empty(
            name: String = "",
            screenWidth: Int = 0,
            screenHeight: Int = 0,
            appPackage: String? = null,
        ): MappingConfig = MappingConfig(
            configName = name,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            bindings = emptyList(),
            createdAt = System.currentTimeMillis(),
            appPackage = appPackage,
        )

        /**
         * 从 JSON 字符串解析配置。
         *
         * @throws IllegalArgumentException JSON 非法或结构不匹配时抛出，附带原始异常信息
         */
        fun fromJson(raw: String): MappingConfig = try {
            KeyMapperJson.instance.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("配置 JSON 解析失败：${e.message}", e)
        }
    }
}
