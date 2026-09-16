package io.legado.app.help.dlna

import java.util.concurrent.atomic.AtomicLong

/**
 * add-dlna-cast AD-16：**会话级分片内存缓存**（LRU，按字节上限淘汰）。
 *
 * 存在理由：投屏链路里"渲染端要一片、代理才去源站取一片"是卡顿的根因——
 * 每片都要串行等待「源站 RTT + 下载耗时」。把分片提前备进内存后，
 * 渲染端请求可**不回源**直接取，响应时间由「源站 RTT + 下载」降为「局域网传输」。
 *
 * 铁律（每条都对应一次设计审查结论，勿随手改）：
 *  1. **按字节上限淘汰**（非按条数）：分片大小差异极大，按条数无法约束内存
 *  2. **整体加锁**：`LinkedHashMap` 的 LRU 结构**非线程安全**，而 NanoHTTPD 每请求一线程、
 *     预取并发写 —— 不加锁会出现结构损坏或条目丢失（红队第 2 轮 P0）
 *  3. **单片过大拒收**：防止单个超大分片把整个缓存挤空（[DlnaConstants.MAX_SEGMENT_BYTES]）
 *  4. **会话级隔离**：实例随会话创建、`teardown` 时清空 —— 不跨会话复用，避免旧数据串扰
 *  5. **条目必须带 MIME**：命中时不回源，响应头只能靠缓存里的 `Content-Type` 才正确
 *
 * @param maxBytes 字节上限（来自用户偏好 `dlnaCacheMb`）；≤0 表示**完全禁用**缓存
 */
class CastSegmentCache(private val maxBytes: Long) {

    /** 一条缓存记录：分片字节 + 该分片响应用的 MIME */
    class Entry(val bytes: ByteArray, val mime: String)

    private val lock = Any()

    /** accessOrder = true → 读到序即为 LRU 序（表头最久未使用） */
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private var currentBytes = 0L

    private val hitCount = AtomicLong()
    private val missCount = AtomicLong()
    private val evictCount = AtomicLong()
    private val prefetchedBytes = AtomicLong()

    /** 是否启用（上限 > 0） */
    val enabled: Boolean get() = maxBytes > 0L

    /** 当前占用字节（诊断用） */
    val bytes: Long get() = synchronized(lock) { currentBytes }

    /** 当前条目数（诊断用） */
    val size: Int get() = synchronized(lock) { entries.size }

    /** 命中数 / 未命中数 / 淘汰数 / 预取字节数（诊断用） */
    val hits: Long get() = hitCount.get()
    val misses: Long get() = missCount.get()
    val evictions: Long get() = evictCount.get()
    val prefetched: Long get() = prefetchedBytes.get()

    /** 是否已缓存（**不计入命中/未命中统计**，供预取调度判断用） */
    fun contains(url: String): Boolean = synchronized(lock) { entries.containsKey(url) }

    /**
     * 取条目；命中同时计入 LRU 访问序（accessOrder 自动完成）。
     *
     * 未命中也会记一次 miss —— 诊断需要"命中率"，而不只是命中数。
     */
    fun get(url: String): Entry? = synchronized(lock) {
        val entry = entries[url]
        if (entry != null) hitCount.incrementAndGet() else missCount.incrementAndGet()
        entry
    }

    /**
     * 写入条目（超额即按 LRU 淘汰）。
     *
     * @return true 表示已缓存；false 表示被拒（缓存禁用 / 单片超上限 / 非法参数）
     */
    fun put(url: String, bytes: ByteArray, mime: String): Boolean = synchronized(lock) {
        if (!enabled) return false
        if (bytes.isEmpty() || bytes.size > DlnaConstants.MAX_SEGMENT_BYTES) return false
        entries.remove(url)?.let { currentBytes -= it.bytes.size }
        entries[url] = Entry(bytes, mime)
        currentBytes += bytes.size
        evictIfNeeded()
        true
    }

    /** 预取成功时记账（用于诊断"预取到底跑了多少"） */
    fun addPrefetchedBytes(count: Long) {
        prefetchedBytes.addAndGet(count)
    }

    /** 清空（会话结束调用） */
    fun clear() = synchronized(lock) {
        entries.clear()
        currentBytes = 0L
    }

    /** 诊断摘要（节流日志用） */
    fun stats(): String =
        "命中=${hitCount.get()} 未命中=${missCount.get()} 淘汰=${evictCount.get()} " +
            "预取=${prefetchedBytes.get() / 1024}KB 占用=${currentBytes / 1024}KB 条目=${entries.size}"

    /** 超限时从最久未使用端淘汰，直到不超上限 */
    private fun evictIfNeeded() {
        if (currentBytes <= maxBytes) return
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= eldest.value.bytes.size
            iterator.remove()
            evictCount.incrementAndGet()
        }
    }
}