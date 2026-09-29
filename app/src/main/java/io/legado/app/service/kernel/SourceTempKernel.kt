package io.legado.app.service.kernel

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * temp 沙箱（web-mcp-productization 二期 · tasks 5.10 / §6.6）。
 *
 * **为什么需要**：MCP 场景下 AI 会反复改书源并复测。若 AI 改动直接进正式列表，用户书架会被
 * 半成品源污染；故 AI 的 `source_save` 一律先落 **temp 沙箱**（正式书源列表不可见），
 * 复测通过后由用户显式 `promote` 才进正式库。
 *
 * 存储：`filesDir/ai_test/temp_sources.json`（JSON 数组，进程重启仍在）。
 * 生命周期：**72h 自动过期**（每次读写时顺带清理）；清理是"静默删除"，
 * 清理前通知见 tasks 7.6（活动通知通道，二期 §7 统一接入）。
 *
 * 已知上限：单文件 JSON 整体读写（AI 一次会话的源数量级很小，百级以内无压力）；
 * 升级路径：量大时改 Room 表。
 */
object SourceTempKernel {

    /** temp 条目存活时长（72 小时）。 */
    const val TTL_MS: Long = 72L * 60 * 60 * 1000

    /** 沙箱目录名（`filesDir/ai_test`）。 */
    const val DIR_NAME = "ai_test"

    /** 沙箱条目。 */
    @Keep
    data class TempSource(
        val url: String = "",
        val name: String = "",
        val json: String = "",
        val savedAt: Long = 0L,
        val note: String? = null,
    )

    private val lock = Any()

    private val storeFile: File
        get() = File(appCtx.filesDir, "$DIR_NAME/temp_sources.json")

    // ============================================================ 读写

    /**
     * 写入 / 覆盖一条 temp 源。
     *
     * 同 `url` 覆盖（AI 反复改同一源时只保留最新版本）；`json` 为空时抛异常。
     */
    suspend fun save(url: String, name: String, json: String, note: String?): TempSource {
        if (url.isBlank()) throw IllegalArgumentException("源地址不能为空")
        if (json.isBlank()) throw IllegalArgumentException("源内容不能为空")
        val entry = TempSource(url = url, name = name, json = json, savedAt = System.currentTimeMillis(), note = note)
        withContext(IO) {
            synchronized(lock) {
                val list = readRaw().filterNot { it.url == url }.toMutableList()
                list.add(entry)
                writeRaw(list)
            }
        }
        return entry
    }

    /** 列出 temp 条目（顺带清理过期项）。 */
    suspend fun list(): List<TempSource> = withContext(IO) {
        synchronized(lock) {
            val all = readRaw()
            val alive = all.filter { !isExpired(it) }
            if (alive.size != all.size) {
                val dropped = all.size - alive.size
                writeRaw(alive)
                AppLog.putInfo("SourceTemp: 沙箱过期清理 entries=$dropped")
            }
            alive.sortedByDescending { it.savedAt }
        }
    }

    /** 丢弃一条 temp 源。 */
    suspend fun discard(url: String): Boolean = withContext(IO) {
        synchronized(lock) {
            val all = readRaw()
            val left = all.filterNot { it.url == url }
            if (left.size != all.size) {
                writeRaw(left)
                true
            } else {
                false
            }
        }
    }

    /**
     * 提交 temp 源进正式库（`parseAndValidate` 同口径校验后落库），成功后移除 temp 条目。
     *
     * 与 App 导入页判据一致：确定性失败的源**拒绝提交**并保留在 temp（便于 AI 继续修改）。
     */
    suspend fun promote(url: String): Map<String, Any?> {
        val entry = list().firstOrNull { it.url == url }
            ?: throw NoSuchElementException("temp 沙箱中不存在该源：$url")
        val validated = BookSourceKernel.parseAndValidate(entry.json)
        val target = validated.accepted.firstOrNull { it.bookSourceUrl == url }
            ?: validated.accepted.firstOrNull()
            ?: throw IllegalStateException("源未通过校验，无法提交：${validated.skipped.firstOrNull()?.reason ?: "未知原因"}")
        BookSourceKernel.saveSource(target)
        discard(url)
        return mapOf(
            "promoted" to true,
            "url" to target.bookSourceUrl,
            "name" to target.bookSourceName,
            "skipped" to validated.skipped.size,
        )
    }

    /** 主动清理过期项，返回清理条数。 */
    suspend fun cleanupExpired(): Int = withContext(IO) {
        synchronized(lock) {
            val all = readRaw()
            val alive = all.filter { !isExpired(it) }
            val dropped = all.size - alive.size
            if (dropped > 0) writeRaw(alive)
            dropped
        }
    }

    // ============================================================ 内部

    private fun isExpired(entry: TempSource): Boolean =
        entry.savedAt > 0 && System.currentTimeMillis() - entry.savedAt > TTL_MS

    private fun readRaw(): MutableList<TempSource> {
        val file = storeFile
        if (!file.exists()) return mutableListOf()
        return kotlin.runCatching {
            GSON.fromJson(file.readText(), Array<TempSource>::class.java)?.toMutableList() ?: mutableListOf()
        }.onFailure {
            AppLog.put("SourceTemp: 沙箱文件损坏，已重置（${it.localizedMessage}）", it)
        }.getOrDefault(mutableListOf())
    }

    private fun writeRaw(list: List<TempSource>) {
        val file = storeFile
        file.parentFile?.mkdirs()
        file.writeText(GSON.toJson(list))
    }
}
