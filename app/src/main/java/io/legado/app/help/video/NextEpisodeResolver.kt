package io.legado.app.help.video

/**
 * 下一集预解析结果缓存（design §1.6 / tasks 6.1）
 *
 * 目的：消除「每次切集都重新嗅探」的固定耗时。命中后管线直接 setUp，跳过三层嗅探。
 *
 * 契约：
 * - 键 = 集原始 URL + 源标识 + 文章链接 的稳定哈希（不含域名明文，可安全写日志）；
 * - LRU 容量 [CAPACITY]（默认 16），TTL [TTL_MS]（默认 10 分钟）；
 * - 失效：播放失败、换源/新会话（[clear]）、手动刷新（[invalidate]）；
 * - 失败不缓存（调用方只在嗅探成功且 finalUrl 非空时 [put]）。
 */
object NextEpisodeResolver {

    const val CAPACITY = 16
    const val TTL_MS = 10 * 60 * 1000L

    /** 预解析结果：可直接 setUp 的最终地址 + 已合并请求头 */
    data class PreResolved(
        val finalUrl: String,
        val headers: Map<String, String>,
        val resolvedAt: Long,
        /** 嗅探引擎标识（fast_direct/page_cache/maccms/dom/webview），仅用于日志归因 */
        val engine: String? = null
    )

    private val cache = object : LinkedHashMap<String, PreResolved>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreResolved>?): Boolean =
            size > CAPACITY
    }

    /**
     * 生成缓存键（纯函数，单测锁定）
     *
     * 入参为空时仍返回稳定键，避免调用方判空分支；哈希只用于关联，不可逆。
     */
    fun key(episodeUrl: String, sourceKey: String?, articleLink: String?): String =
        VideoTrace.hash8("$episodeUrl|${sourceKey.orEmpty()}|${articleLink.orEmpty()}")

    @Synchronized
    fun put(key: String, value: PreResolved) {
        if (key.isBlank() || value.finalUrl.isBlank()) return
        cache[key] = value
    }

    /** 取未过期结果；过期即删除并返回 null */
    @Synchronized
    fun get(key: String, now: Long = android.os.SystemClock.uptimeMillis()): PreResolved? {
        val value = cache[key] ?: return null
        if (now - value.resolvedAt >= TTL_MS) {
            cache.remove(key)
            return null
        }
        return value
    }

    @Synchronized
    fun invalidate(key: String) {
        cache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    internal fun sizeForTest(): Int = cache.size
}
