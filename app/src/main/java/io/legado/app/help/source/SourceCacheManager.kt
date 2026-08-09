package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig

/**
 * M3 SourceCacheManager — 统一 WebView 缓存策略
 *
 * 机制互补：抽取 RssSource 的 cacheFirst WebView 缓存模式设置为共享组件，
 * BookSource 视频源 WebView 通过 AppConfig 获得同样的缓存优先能力。
 *
 * 缓存策略：
 * - RssSource 读自身 cacheFirst 字段（默认 true）
 * - BookSource 读 AppConfig.bookSourceCacheFirst 全局配置（默认 false=沿用现有行为）
 */
object SourceCacheManager {

    /**
     * 判断源是否应使用缓存优先模式
     * @param source 源（RssSource 读自身字段，BookSource 读 AppConfig 全局配置）
     * @return true=缓存优先（LOAD_CACHE_ELSE_NETWORK），false=默认（LOAD_DEFAULT）
     */
    fun isCacheFirst(source: BaseSource): Boolean {
        return when (source) {
            is RssSource -> source.cacheFirst
            is BookSource -> AppConfig.bookSourceCacheFirst
            else -> false
        }
    }
}
