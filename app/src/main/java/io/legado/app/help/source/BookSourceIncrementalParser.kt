package io.legado.app.help.source

import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonReader
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * AD-22 薄边界：**带上限的流式读取**（供大文件导入复用）。
 *
 * 与通用「万能 parse 层」的区别：只做一件事 —— 逐次放行字节并在超出 [maxBytes] 时**立刻抛出**，
 * 从而不把整个文件读进内存（REQ-05 的 DoS 防护面）。W4 的 OPML 导入复用本类做同一防护。
 */
class LimitedInputStream(source: InputStream, private val maxBytes: Long) : FilterInputStream(source) {

    /** 已放行字节数（供调用方/测试观测）。上游超限时不再递增。 */
    var bytesRead: Long = 0L
        private set

    private fun account(added: Long) {
        if (added <= 0L) return
        bytesRead += added
        if (bytesRead > maxBytes) {
            throw ImportLimitExceededException(
                "文件超过上限 ${maxBytes / 1024 / 1024}MB，已中止导入（未完整读入内存）"
            )
        }
    }

    override fun read(): Int {
        val b = super.read()
        if (b != -1) account(1L)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        account(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        account(skipped)
        return skipped
    }
}

/** 导入上限被突破（超字节数 / 超条目数）。 */
class ImportLimitExceededException(message: String) : IOException(message) {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }
}

/**
 * 单条解析失败，携带**条目序号**（1 起）便于在超大合集中定位出错项。
 * 原始实现只抛 Gson 的语法异常，无法指出是第几条 —— 万条合集里无从排查。
 */
class ImportItemParseException(
    val index: Int,
    cause: Throwable
) : IOException("第 ${index + 1} 条解析失败：${cause.localizedMessage}", cause) {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }
}

/**
 * 书源 JSON 数组的**增量（流式）解析**（REQ-05 / AD-13）。
 *
 * 与一次性 `GSON.fromJsonArray<BookSource>(...)` 的差异：
 * - **不**把整份原始 JSON 文本与中间 `List<T?>` 副本同时常驻；逐条解析后立即交给 [onEach]；
 * - 读取阶段即校验 [maxBytes]（字节）与 [maxCount]（条目数），超限直接拒绝（AD-18）；
 * - 每条解析失败带条目序号（[ImportItemParseException]）。
 *
 * **能力边界（诚实披露，勿当 O(1)）**：调用方（如 `ImportBookSourceViewModel`）仍需把结果
 * 累积进其成员列表供查重/勾选使用 ⇒ 峰值仍随条目数增长；本函数消除的是「原始文本 + 中间
 * 列表副本」这两份额外常驻。
 *
 * 说明：签名取 `InputStream`（而非 `Reader`）是为了能**精确按字节**计量上限
 * （`Reader` 层只能按字符计，对 UTF-8 中文会低估 2-3 倍）；行为与既有
 * `GSON.fromJsonArray(InputStream)` 一致 —— 同样以 UTF-8 解码。
 */
object BookSourceIncrementalParser {

    /** 单次导入的最大字节数（32MB）：读取阶段即拒绝。 */
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

    /** 单次导入的最大条目数。 */
    const val MAX_IMPORT_COUNT = 20_000

    /**
     * 逐条解析 JSON 数组中的书源。
     *
     * @param onEach 每解析出一条即回调（调用方负责累积与校验）
     * @return 实际解析出的条目数（空数组返回 0）
     * @throws ImportLimitExceededException 超出 [maxBytes] 或 [maxCount]
     * @throws ImportItemParseException 某一条无法解析（含 null 元素）
     */
    suspend fun parseBookSourcesIncremental(
        input: InputStream,
        onEach: (BookSource) -> Unit,
        maxBytes: Long = MAX_IMPORT_BYTES,
        maxCount: Int = MAX_IMPORT_COUNT
    ): Int {
        val reader = JsonReader(InputStreamReader(LimitedInputStream(input, maxBytes), Charsets.UTF_8))
        var count = 0
        try {
            reader.beginArray()
            while (reader.hasNext()) {
                currentCoroutineContext().ensureActive()
                if (count >= maxCount) {
                    throw ImportLimitExceededException(
                        "导入条数超过上限 $maxCount 条，已中止（防超大合集）"
                    )
                }
                val item: BookSource? = try {
                    GSON.fromJson(reader, BookSource::class.java)
                } catch (e: ImportLimitExceededException) {
                    // 字节上限可能在**条目解析中途**触发（读取缓冲填满）⇒ 必须原样上抛，
                    // 不可误报为「第 N 条解析失败」
                    throw e
                } catch (e: Exception) {
                    throw ImportItemParseException(count, e)
                }
                if (item == null) {
                    // 与原 fromJsonArray 的 null 元素口径一致
                    throw ImportItemParseException(
                        count,
                        JsonSyntaxException(
                            "列表不能存在null元素，可能是json格式错误，通常为列表存在多余的逗号所致"
                        )
                    )
                }
                onEach(item)
                count++
            }
            reader.endArray()
        } finally {
            kotlin.runCatching { reader.close() }
        }
        return count
    }

    /**
     * [parseBookSourcesIncremental] 的「并入目标列表 + 失败回滚」包装（书源导入的统一语义）。
     *
     * 抽为**单源**的原因：三条导入路径需要完全一致的语义 —— 空数组无操作、首条空 URL 判「不是书源」、
     * 失败**不留半成品**；各调用点各写一遍必然漂移（本仓「三处同名同文件」类漂移已多次发生）。
     *
     * @param requireNonEmptyUrlFirst 是否校验首条 `bookSourceUrl` 非空（书源「不是书源」口径）
     * @return 实际并入的条目数（空数组为 0，此时不写入、不报错）
     * @throws ImportLimitExceededException / ImportItemParseException / NoStackTraceException
     */
    suspend fun parseBookSourcesIncrementalInto(
        input: InputStream,
        target: MutableList<BookSource>,
        requireNonEmptyUrlFirst: Boolean = true,
        maxBytes: Long = MAX_IMPORT_BYTES,
        maxCount: Int = MAX_IMPORT_COUNT
    ): Int {
        val startSize = target.size
        try {
            val count = parseBookSourcesIncremental(input, { target.add(it) }, maxBytes, maxCount)
            if (count == 0) return 0
            if (requireNonEmptyUrlFirst && target[startSize].bookSourceUrl.isEmpty()) {
                throw NoStackTraceException("不是书源")
            }
            return count
        } catch (e: Throwable) {
            // 回滚本次已并入的条目：失败不留半成品（原实现失败时同样不改动目标列表）
            while (target.size > startSize) {
                target.removeAt(target.size - 1)
            }
            throw e
        }
    }
}