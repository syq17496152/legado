package io.legado.app.help.dlna

/**
 * dlna-cast-cache-unify AD-06：**Range 解析与命中判定**（纯逻辑，可 JVM 单测）。
 *
 * 为什么把它从 `CastProxyServer` 里抽出来：
 *  1. L1（内存缓存）与 L2（共享播放器缓存）都需要"这次请求要哪些字节"，口径必须**唯一**
 *     —— 分成两份实现迟早会漂移，进而出现"缓存说命中、响应却错位"
 *  2. 越界/畸形/多区间这些边界必须可自动化验证（JVM 单测），不能只靠真机电视
 *
 * 铁律：
 *  - 只支持**单区间**（`bytes=a-b` / `bytes=a-` / `bytes=-n`）：多区间按 RFC 7233 允许的方式
 *    **忽略**（回 200 全量），不做 `multipart/byteranges`（电视播放器基本只用单区间）
 *  - `total` 不可知（≤0）时一律按"整段"处理，绝不猜区间
 *  - 本文件**不得**依赖 Android API（[parse] 的输入全部来自请求头与上游响应头）
 */
object CastRangeSpec {

    /** 解析出的区间；[partial] = false 表示客户端没要区间（回整段） */
    data class Range(val start: Long, val end: Long, val partial: Boolean) {
        /** 区间字节数（整段时 = total） */
        val length: Long get() = end - start + 1
    }

    /**
     * 解析 `Range` 头。
     *
     * @return **null 表示区间越界**（调用方应回 416）；否则返回可服务的区间
     */
    fun parse(header: String?, total: Long): Range? {
        if (header.isNullOrBlank() || total <= 0L) return Range(0, total - 1, false)
        val spec = header.trim()
        if (!spec.startsWith("bytes=", true)) return Range(0, total - 1, false)
        val value = spec.substringAfter('=').trim()
        if (value.contains(',')) return Range(0, total - 1, false) // 多区间 → 忽略
        val dash = value.indexOf('-')
        if (dash < 0) return Range(0, total - 1, false)
        val startText = value.substring(0, dash).trim()
        val endText = value.substring(dash + 1).trim()
        return when {
            startText.isEmpty() && endText.isEmpty() -> Range(0, total - 1, false)

            startText.isEmpty() -> {
                // 后缀区间 bytes=-n：取最后 n 字节
                val suffix = endText.toLongOrNull() ?: return Range(0, total - 1, false)
                if (suffix <= 0L) return null
                val start = (total - suffix).coerceAtLeast(0L)
                Range(start, total - 1, true)
            }

            else -> {
                val start = startText.toLongOrNull() ?: return Range(0, total - 1, false)
                if (start >= total) return null // 越界 → 416
                val end = if (endText.isEmpty()) {
                    total - 1
                } else {
                    (endText.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
                }
                if (end < start) return null
                Range(start, end, true)
            }
        }
    }

    /** 组装 `Content-Range` 头值 */
    fun contentRange(start: Long, end: Long, total: Long): String = "bytes $start-$end/$total"

    /**
     * L1 写入前的**完整性判定**（REQ-8 铁律）。
     *
     * 背景：预取读到一半被中断（或上游提前收尾）时会拿到**被截断的字节**；
     * 若把它塞进 L1，后续命中就会按 `bytes.size` 回一个"长度不足的 200"，
     * 电视端会当成完整分片解码 —— 这是本设计中最危险的数据正确性问题。
     * 因此：**只有读满预期长度才允许进 L1**；长度不可知（≤0）一律不写 L1。
     *
     * @param readBytes 实际读到的字节数
     * @param expectedBytes 上游 `Content-Length`（≤0 表示不可知）
     */
    fun isComplete(readBytes: Long, expectedBytes: Long): Boolean =
        expectedBytes > 0L && readBytes == expectedBytes

    /**
     * L2 是否**整段命中**：`Cache.getCachedBytes(key, position, length)` 的返回值语义
     * （javap 实证：未命中返回 **0**，不是 -1）。
     *
     * @param cachedBytes [androidx.media3.datasource.cache.Cache.getCachedBytes] 的返回
     * @param wanted 请求的字节数（未知长度场景传 -1，表示"从 position 到末尾"）
     */
    fun isFullyCached(cachedBytes: Long, wanted: Long): Boolean =
        cachedBytes > 0L && (wanted < 0L || cachedBytes >= wanted)
}