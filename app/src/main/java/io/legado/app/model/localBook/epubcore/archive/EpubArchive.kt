package io.legado.app.model.localBook.epubcore.archive

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.util.Locale

/**
 * EPUB 压缩包只读访问抽象（迁移自 archive v15，为我方同名接口的**超集**）。
 *
 * 新增能力：大小写不敏感 + 路径归一化的 [canonicalPath]、按需流式读取 [openStream]、
 * 条目原始字节数 [entrySize]（默认实现回退到 `readBytes`）。
 */
interface EpubArchive : Closeable {
    fun exists(path: String): Boolean
    fun list(): List<String>

    /** 返回 archive 内真实存在的条目路径（精确匹配优先，其次大小写不敏感），找不到返回 null。 */
    fun canonicalPath(path: String): String? {
        val normalized = EpubPath.normalize(path)
        var caseInsensitiveMatch: String? = null
        list().forEach { entry ->
            val candidate = EpubPath.normalize(entry)
            if (candidate == normalized) return candidate
            if (caseInsensitiveMatch == null &&
                candidate.lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT)
            ) {
                caseInsensitiveMatch = candidate
            }
        }
        return caseInsensitiveMatch
    }

    fun readBytes(path: String, maxBytes: Long = DEFAULT_MAX_ENTRY_BYTES): ByteArray

    /** 条目解压后字节数；未知返回 null（默认实现不提供）。 */
    fun entrySize(path: String): Long? = null

    /** 流式打开条目（默认把 [readBytes] 结果包成内存流）。 */
    fun openStream(path: String): InputStream = ByteArrayInputStream(readBytes(path))

    fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    companion object {
        const val DEFAULT_MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    }
}