package io.legado.app.help.md

import io.legado.app.help.config.AtomicTextFileStore
import java.io.File
import java.security.MessageDigest

/**
 * Markdown 产物缓存（epub-md-rich-rendering 阶段 3.11；blueprint §五）。
 *
 * **为什么需要**：md → HTML 的转换含 commonmark 解析与 mermaid/KaTeX 注入脚本拼装，
 * 每次开章重算会拖慢翻页；而缓存落盘必须**防半成品**（进程被杀/磁盘满 ⇒ 下次读到截断文件，
 * 表现为"章节内容莫名缺失"）。
 *
 * 契约：
 * - **原子写**：复用既有 [AtomicTextFileStore]（staging → 校验 → 原子替换，失败保留 backup）；
 * - **键**：`sha256(bookUrl|length|lastModified|schemaVersion)` ⇒ 书被替换/升级 schema 后自然失效，
 *   **不依赖 mtime 之外的弱判据**（避免"改了文件仍读旧缓存"）；
 * - **容量上限**：[maxBytes]（默认 200MB），超限按 **lastModified 由旧到新**回收至阈值以下；
 * - **清理入口**：[clear] 供"清除缓存"入口调用。
 *
 * 纯 JVM（`java.io`/`java.security`）⇒ 可在临时目录上单测原子性与回收。
 */
internal class MdCacheStore(
    private val root: File,
    private val maxBytes: Long = DefaultMaxBytes,
    private val schemaVersion: Int = SchemaVersion
) {

    /** 缓存键：书标识 + 文件指纹 + schema 版本。 */
    fun key(bookUrl: String, length: Long, lastModified: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = "$bookUrl|$length|$lastModified|v$schemaVersion"
        return digest.digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun read(key: String): String? {
        val file = fileOf(key)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** 原子写入；返回是否成功（失败不抛，交由调用方降级为"不缓存"）。 */
    fun write(key: String, content: String): Boolean {
        return runCatching {
            AtomicTextFileStore(fileOf(key)).writeVerified(content) { it == content }
            true
        }.getOrDefault(false)
    }

    fun delete(key: String): Long {
        return runCatching { AtomicTextFileStore(fileOf(key)).delete() }.getOrDefault(0L)
    }

    /**
     * 回收至容量阈值以下（按 lastModified 由旧到新删除）。
     *
     * @return 删除的条目数。
     */
    fun trim(): Int {
        val files = listEntries()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        var removed = 0
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return removed
            val size = file.length()
            val key = file.name.removeSuffix(Suffix)
            if (delete(key) >= 0 && !file.exists()) {
                total -= size
                removed++
            }
        }
        return removed
    }

    fun clear(): Int {
        val files = listEntries()
        files.forEach { file -> file.delete() }
        return files.size
    }

    fun totalBytes(): Long = listEntries().sumOf { it.length() }

    fun entryCount(): Int = listEntries().size

    private fun listEntries(): List<File> {
        val dir = root
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(Suffix) }?.toList().orEmpty()
    }

    private fun fileOf(key: String): File = File(root, "$key$Suffix")

    companion object {
        const val SchemaVersion = 1
        const val DefaultMaxBytes = 200L * 1024 * 1024
        private const val Suffix = ".md.html"
    }
}