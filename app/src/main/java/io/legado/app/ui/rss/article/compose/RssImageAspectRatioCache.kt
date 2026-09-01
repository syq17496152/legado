package io.legado.app.ui.rss.article.compose

import androidx.collection.LruCache
import io.legado.app.help.CacheManager

/**
 * 三代 Adapter 宽高比缓存原样移植（design-b3-d4-flagship §2.4，源自 RssArticlesAdapter3 companion）：
 * LruCache(399) 内存层 + CacheManager 20 天持久化层，key 前缀 img_ar_ / 有效期不变（覆盖安装老缓存兼容）。
 * ratio 语义 = height / width。
 */
object RssImageAspectRatioCache {
    private const val KEY_NAME = "img_ar_"
    private const val SAVE_TIME = 60 * 60 * 24 * 20 // 20天
    private val lru = LruCache<String, Float>(399)

    fun get(url: String): Float = lru[url] ?: CacheManager.getFloat(KEY_NAME + url)?.also {
        lru.put(url, it)
    } ?: 0f

    fun put(url: String, ratio: Float) {
        if (ratio <= 0f) return
        lru.put(url, ratio)
        CacheManager.put(KEY_NAME + url, ratio, SAVE_TIME)
    }
}
