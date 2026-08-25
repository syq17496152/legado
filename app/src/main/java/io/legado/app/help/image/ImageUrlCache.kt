package io.legado.app.help.image

import io.legado.app.constant.AppLog
import io.legado.app.utils.ACache
import org.json.JSONArray

/**
 * 图片订阅源「文章 → 图片URL列表」解析结果缓存
 *
 * 背景（rss-image-load-optimization）：
 * 图片订阅源每次切换文章都重新网络请求文章页（Rss.getContentAwait 无缓存），
 * 且 L1 静态解析 < 3 张时触发 L2 WebView 嗅探（最多 6s），是加载慢的首要瓶颈。
 * 本缓存参考书源「内容缓存归属」思路，缓存解析结果，二次进入同一文章直接命中。
 *
 * 结构：
 * - 内存：LinkedHashMap（LRU 访问序），容量上限 200 条，TTL 24h
 * - 磁盘：ACache（自带 saveTime TTL + 容量约束），持久化复用
 *
 * key：article.link 的 hashCode（与 ImageCanvasAdapter 的 url.hashCode 去重用法一致）
 */
object ImageUrlCache {

    private const val CACHE_NAME = "imageUrlCache"
    private const val KEY_PREFIX = "img_"
    private const val TTL_MS = 24 * 60 * 60 * 1000L // 24h

    /** 内存缓存容量上限（超出淘汰最久未访问） */
    private const val MEM_MAX_ENTRIES = 200

    private data class Entry(val urls: List<String>, val timestamp: Long)

    private val memCache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MEM_MAX_ENTRIES
    }

    private val diskCache by lazy { ACache.get(CACHE_NAME) }

    private fun key(articleLink: String): String = KEY_PREFIX + articleLink.hashCode()

    /**
     * 读取缓存
     *
     * @param articleLink 文章链接（缓存 key 来源）
     * @return 图片 URL 列表；未命中或过期返回 null
     */
    fun get(articleLink: String?): List<String>? {
        if (articleLink.isNullOrBlank()) return null
        val cacheKey = key(articleLink)
        // 内存命中（校验 TTL）
        memCache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < TTL_MS) {
                return entry.urls
            }
            memCache.remove(cacheKey)
        }
        // 磁盘命中（ACache 内部按 saveTime 自动清理过期）
        val json = diskCache.getAsString(cacheKey) ?: return null
        return kotlin.runCatching {
            val arr = JSONArray(json)
            val urls = (0 until arr.length()).map { arr.getString(it) }
            memCache[cacheKey] = Entry(urls, System.currentTimeMillis())
            urls
        }.getOrElse {
            AppLog.put("ImageUrlCache read failed: $cacheKey ${it.localizedMessage}")
            null
        }
    }

    /**
     * 写入缓存
     *
     * @param articleLink 文章链接
     * @param urls 解析到的图片 URL 列表
     */
    fun put(articleLink: String?, urls: List<String>) {
        if (articleLink.isNullOrBlank() || urls.isEmpty()) return
        val cacheKey = key(articleLink)
        memCache[cacheKey] = Entry(urls, System.currentTimeMillis())
        kotlin.runCatching {
            val arr = JSONArray()
            urls.forEach { arr.put(it) }
            diskCache.put(cacheKey, arr.toString(), (TTL_MS / 1000).toInt())
        }.onFailure {
            AppLog.put("ImageUrlCache write failed: $cacheKey ${it.localizedMessage}")
        }
    }

    /**
     * 清空全部缓存（内存 + 磁盘）
     */
    fun clear() {
        memCache.clear()
        kotlin.runCatching { diskCache.clear() }
    }
}
