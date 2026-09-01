package io.legado.app.help.source

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ACache
import splitties.init.appCtx
import java.io.File

/**
 * 书源脚本缓存命名空间（P0-S2，对齐 NG BookSourceCacheStore，D7 裁剪 registry 记账改前缀清理）
 *
 * JS bindings["cache"] 按源隔离：DB（caches 表 storagePrefix 前缀键）+ 内存（memoryLruCache 前缀键）
 * + 文件（cacheDir/bookSourceCache/{ns}/ 专属目录）三处，删除源时按前缀/目录整体清理。
 * 开关 bookSourceCacheScoped（AppConfig 实时读，默认关=现状全局 CacheManager）。
 * 注意与文件沙箱根（externalCache/source/{ns}）为两根互不相干（D12）。
 */
@Keep
@Suppress("unused")
class BookSourceCacheStore(sourceUrl: String) {

    private val namespace = BookSourceStorageScope.namespace(sourceUrl)
    private val storagePrefix = "book_source_cache_$namespace:"

    // 文件缓存根 = cacheDir/bookSourceCache/{ns}（两根之一，≠文件沙箱根）
    private val fileCache by lazy {
        ACache.get(File(appCtx.cacheDir, "bookSourceCache${File.separator}$namespace"))
    }

    private fun scopedKey(key: String): String = storagePrefix + key

    /**
     * saveTime 单位为秒
     */
    fun put(key: String, value: Any, saveTime: Int = 0) {
        when (value) {
            is ByteArray -> fileCache.put(scopedKey(key), value, saveTime)
            else -> CacheManager.put(scopedKey(key), value, saveTime)
        }
    }

    fun putMemory(key: String, value: Any) {
        CacheManager.putMemory(scopedKey(key), value)
    }

    fun getFromMemory(key: String): Any? {
        return CacheManager.getFromMemory(scopedKey(key))
    }

    fun deleteMemory(key: String) {
        CacheManager.deleteMemory(scopedKey(key))
    }

    fun get(key: String): String? {
        return CacheManager.get(scopedKey(key))
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        return CacheManager.get(scopedKey(key), onlyDisk)
    }

    fun getInt(key: String): Int? {
        return CacheManager.getInt(scopedKey(key))
    }

    fun getLong(key: String): Long? {
        return CacheManager.getLong(scopedKey(key))
    }

    fun getDouble(key: String): Double? {
        return CacheManager.getDouble(scopedKey(key))
    }

    fun getFloat(key: String): Float? {
        return CacheManager.getFloat(scopedKey(key))
    }

    fun getByteArray(key: String): ByteArray? {
        return fileCache.getAsBinary(scopedKey(key))
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        fileCache.put(scopedKey(key), value, saveTime)
    }

    fun getFile(key: String): String? {
        return fileCache.getAsString(scopedKey(key))
    }

    fun delete(key: String) {
        CacheManager.delete(scopedKey(key))
        fileCache.remove(scopedKey(key))
    }

    internal companion object {

        /**
         * 删源联动清理（D7 三步）：DB 前缀删 → 内存前缀删 → 文件目录 clear
         * 调用方须以 kotlin.runCatching 包裹，失败仅记 SourceCache 日志不阻断删源（E17）
         */
        fun clear(sourceUrl: String) {
            val ns = BookSourceStorageScope.namespace(sourceUrl)
            val prefix = "book_source_cache_$ns:"
            appDb.cacheDao.deleteByPrefix(prefix)
            CacheManager.deleteMemoryByPrefix(prefix)
            kotlin.runCatching {
                ACache.get(File(appCtx.cacheDir, "bookSourceCache${File.separator}$ns")).clear()
            }.onFailure {
                AppLog.putDebugWithTag(
                    AppLog.TAG_SOURCE_CACHE,
                    "clear fileCache failed ns=${ns.take(8)} err=${it.localizedMessage}",
                    null,
                    AppLog.Level.WARN
                )
            }
        }
    }
}

/**
 * P0-S2 cache 绑定选择：BookSource 上下文且开关开启 → 按源 BookSourceCacheStore；
 * RssSource/纯 JS 加密任务/开关关闭 → 现状全局 CacheManager（零行为变化）
 */
internal fun BaseSource?.scriptCacheObject(): Any =
    (this as? BookSource)
        ?.takeIf { AppConfig.bookSourceCacheScoped }
        ?.let { BookSourceCacheStore(it.bookSourceUrl) }
        ?: CacheManager
