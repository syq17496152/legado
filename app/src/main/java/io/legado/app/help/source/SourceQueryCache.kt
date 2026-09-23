package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig

/**
 * R18（B4）通用「TTL + epoch」缓存核心（纯 Kotlin，可 JVM 单测）。
 *
 * 语义：命中需**同时**满足 ①条目 epoch 与当前 epoch 一致 ②未超 TTL。
 * - epoch 由 [invalidate] 自增 ⇒ 任一写源入口调用后，此前写入的条目**立即全部失效**
 *   （不依赖 TTL 兜底，保证「写完立即读到最新」）；
 * - TTL 是兜底：写源若绕过所有登记入口（如直接 SQL / 外部工具改库），最迟一个 TTL 后自愈；
 * - [loadCount] 记录**回库次数**（未命中次数），供单测断言「N 次连读 = 1 次回库」。
 *
 * 线程安全：全部方法 `@Synchronized`（读路径来自 WebView/图片加载线程，写路径来自 DB 协程）。
 */
internal class TtlEpochCache<V : Any>(
    private val ttlMs: Long,
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private class Entry<V>(val value: V, val epoch: Long, val at: Long)

    private val store = LinkedHashMap<String, Entry<V>>()
    private var epoch = 0L

    /** 回库（未命中）次数 */
    var loadCount: Int = 0
        private set

    fun getOrLoad(key: String, loader: () -> V?): V? {
        synchronized(this) {
            val now = clock()
            store[key]?.let { entry ->
                if (entry.epoch == epoch && now - entry.at <= ttlMs) {
                    return entry.value
                }
            }
            loadCount++
        }
        // ⚠ 回库在锁外执行：loader 会查数据库，持锁查询会与写源路径互相阻塞
        val loaded = loader() ?: return null
        put(key, loaded)
        return loaded
    }

    fun put(key: String, value: V) {
        synchronized(this) {
            if (store.size >= maxSize && !store.containsKey(key)) {
                store.entries.firstOrNull()?.let { store.remove(it.key) }
            }
            store[key] = Entry(value, epoch, clock())
        }
    }

    /** 失效出口：epoch++ 并清空（调用方 = 各写源入口） */
    fun invalidate() {
        synchronized(this) {
            epoch++
            store.clear()
        }
    }

    fun currentEpoch(): Long = synchronized(this) { epoch }

    fun size(): Int = synchronized(this) { store.size }

    fun isEmpty(): Boolean = synchronized(this) { store.isEmpty() }

    private companion object {
        const val DEFAULT_MAX_SIZE = 64
    }
}

/**
 * R18（B4）书源查询短时缓存（进程内）。
 *
 * 改造动因：`SourceHelp.getSource` 是 WebView 源验证与图片加载的**高频读路径**，
 * 原先每次都回库；长跑下主线程/IO 线程反复查同一行（书源表不大但查询含 JSON 列解析）。
 *
 * 一致性设计（**唯一失效出口** = [invalidate]，对标 G0-2.1）：
 * - 登记在六类写源入口：①新增/编辑源（`*SourceEditViewModel`）②删除源（`SourceHelp.delete*Internal`）
 *   ③批量导入（`ImportOldData` / `SourceHelp.insert*`）④备份恢复（`Restore`）⑤内置源自动更新
 *   （`RuleUpdate` 经 `SourceHelp.insertBookSource` ⇒ 自动覆盖）⑥回收站还原/质量修复（`SourceRecycleBinHelp` / `QualityReportApplier`）
 * - TTL [TTL_MS] 为兜底（见 `TtlEpochCache` 类注释）；
 * - 开关 `AppConfig.sourceQueryCacheEnabled` 关闭 ⇒ 直接回库（行为与改造前一致）。
 *
 * 已知上限：缓存对象为 `BaseSource` 实例引用（编辑后由 invalidate 整表失效，不做单条增量更新）。
 * 升级路径：如需更低失效代价，可改为「按 key 失效 + 写入口传 key」。
 */
object SourceQueryCache {

    /**
     * 兜底 TTL（毫秒）。
     *
     * 取 2s：远小于用户「编辑源 → 立即可见」的感知阈值，同时覆盖同一次操作内的连续读
     * （如 WebView 一次加载会多次 `getSource`）。
     */
    const val TTL_MS = 2_000L

    private val cache = TtlEpochCache<BaseSource>(TTL_MS)

    /** 失效出口（epoch++）。任一写源入口完成后必须调用 */
    fun invalidate() {
        cache.invalidate()
    }

    /**
     * 按 key 取源（缓存命中直接返回，未命中回库并写入缓存）。
     *
     * @param cacheKey 需**带类型前缀**（`book:` / `rss:` / `any:`）——书源与订阅源的 key 空间不同但可能同串
     */
    fun get(cacheKey: String, loader: () -> BaseSource?): BaseSource? {
        if (!AppConfig.sourceQueryCacheEnabled) {
            return loader()
        }
        return cache.getOrLoad(cacheKey) { loader() }
    }

    /** 回库次数（单测/诊断用） */
    internal fun loadCount(): Int = cache.loadCount

    internal fun cacheSize(): Int = cache.size()
}