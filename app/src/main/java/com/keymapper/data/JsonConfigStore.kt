package com.keymapper.data

import android.content.Context
import android.net.Uri
import com.keymapper.domain.model.KeyMapperJson
import com.keymapper.domain.model.MappingConfig
import com.keymapper.util.Constants
import java.io.File
import kotlinx.serialization.encodeToString

/**
 * 配置存储异常：所有 IO / 解析失败都会被包装成本异常，调用方只需捕获一种类型。
 */
class ConfigStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 基于 `kotlinx.serialization.json` 的 JSON 读写（P0-7 / P1-6）。
 *
 * 存储位置：`Context.getFilesDir()/configs/<sanitized-name>.json`
 * 导入导出：由调用方（Activity）用 SAF 拿到 [Uri] 后交给 [readFromUri] / [writeToUri]。
 *
 * 注意：本类只做同步 IO，线程调度由 [ConfigRepository] 统一用 `Dispatchers.IO` 处理。
 */
class JsonConfigStore(private val ctx: Context) {

    private val json = KeyMapperJson.instance

    /** 配置目录，惰性创建。 */
    fun configDir(): File {
        val dir = File(ctx.filesDir, Constants.CONFIG_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 把配置名转成安全文件名：非字母/数字/下划线/连字符的字符统一替换为下划线。
     * 中文等 Unicode 字母会被保留（`\p{L}`），保证「王者荣耀方案.json」这类文件名可读。
     */
    fun fileNameOf(configName: String): String {
        val safe = configName.trim()
            .replace(Regex("[^\\p{L}\\p{N}_\\-]"), "_")
            .take(80)
            .ifEmpty { "config" }
        return safe + Constants.CONFIG_FILE_EXTENSION
    }

    /** 指定配置名对应的文件。 */
    fun fileOf(configName: String): File = File(configDir(), fileNameOf(configName))

    /** 配置是否已存在。 */
    fun exists(configName: String): Boolean = fileOf(configName).isFile

    /**
     * 读取一份配置。
     *
     * @return 文件不存在时返回 null
     * @throws ConfigStoreException 文件存在但内容无法解析
     */
    fun read(name: String): MappingConfig? {
        val file = fileOf(name)
        if (!file.isFile) return null
        val raw = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ConfigStoreException("读取配置文件失败：${file.name}", e)
        }
        return deserialize(raw)
    }

    /**
     * 写入（覆盖）一份配置。
     *
     * @throws ConfigStoreException 配置名为空或写盘失败
     */
    fun write(cfg: MappingConfig) {
        if (cfg.configName.isBlank()) {
            throw ConfigStoreException("配置名不能为空，无法保存")
        }
        val file = fileOf(cfg.configName)
        try {
            file.writeText(serialize(cfg), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ConfigStoreException("写入配置文件失败：${file.name}", e)
        }
    }

    /**
     * 删除一份配置。
     *
     * @return 是否真正删除了文件
     */
    fun delete(name: String): Boolean {
        val file = fileOf(name)
        if (!file.isFile) return false
        return file.delete()
    }

    /**
     * 列出全部配置名。
     *
     * 优先返回文件内部的 `configName`（保留用户原始命名，含空格等字符），
     * 解析失败时回退为文件名主干，保证坏文件也能在列表中显示并被删除。
     */
    fun listNames(): List<String> {
        val files = configDir().listFiles { f: File ->
            f.isFile && f.name.endsWith(Constants.CONFIG_FILE_EXTENSION, ignoreCase = true)
        } ?: return emptyList()

        return files.sortedBy { it.name.lowercase() }.map { file ->
            val fallback = file.name.removeSuffix(Constants.CONFIG_FILE_EXTENSION)
            try {
                val cfg = MappingConfig.fromJson(file.readText(Charsets.UTF_8))
                cfg.configName.ifBlank { fallback }
            } catch (e: Exception) {
                fallback
            }
        }.distinct()
    }

    /**
     * 读取全部配置（用于 P1-1 按前台包名匹配）。无法解析的文件被跳过。
     */
    fun readAll(): List<MappingConfig> {
        val files = configDir().listFiles { f: File ->
            f.isFile && f.name.endsWith(Constants.CONFIG_FILE_EXTENSION, ignoreCase = true)
        } ?: return emptyList()

        return files.sortedBy { it.name.lowercase() }.mapNotNull { file ->
            try {
                MappingConfig.fromJson(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 序列化为 JSON 文本。 */
    fun serialize(cfg: MappingConfig): String = json.encodeToString(cfg)

    /**
     * 反序列化 JSON 文本。
     *
     * 缺失字段走 data class 默认值；字段类型/结构非法时抛出 [ConfigStoreException]（P0-7）。
     */
    fun deserialize(raw: String): MappingConfig = try {
        MappingConfig.fromJson(raw)
    } catch (e: Exception) {
        throw ConfigStoreException(e.message ?: "配置 JSON 解析失败", e)
    }

    /**
     * 从 SAF [Uri] 导入（P1-6）。
     *
     * @throws ConfigStoreException 流不可读或内容非法
     */
    fun readFromUri(uri: Uri): MappingConfig {
        val raw = try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: throw ConfigStoreException("无法打开所选文件（输入流为空）")
        } catch (e: ConfigStoreException) {
            throw e
        } catch (e: Exception) {
            throw ConfigStoreException("读取所选文件失败：${e.message}", e)
        }
        return deserialize(raw)
    }

    /**
     * 导出到 SAF [Uri]（P1-6）。
     *
     * @throws ConfigStoreException 流不可写
     */
    fun writeToUri(cfg: MappingConfig, uri: Uri) {
        try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(serialize(cfg).toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw ConfigStoreException("无法写入所选位置（输出流为空）")
        } catch (e: ConfigStoreException) {
            throw e
        } catch (e: Exception) {
            throw ConfigStoreException("导出文件失败：${e.message}", e)
        }
    }
}
