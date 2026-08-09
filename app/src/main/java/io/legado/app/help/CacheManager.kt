package io.legado.app.help

import android.content.ComponentCallbacks2
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
import androidx.collection.LruCache
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cache
import io.legado.app.model.analyzeRule.QueryTTF
import io.legado.app.utils.ACache
import io.legado.app.utils.memorySize
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking

private val queryTTFMap = LruCache<String, QueryTTF>(4)

private const val M = 1024 * 1024

// B13: 内存缓存上限随堆大小动态调整，小内存设备减少 OOM 风险
private fun memoryCacheSize(): Int {
    return (MemoryPressure.maxMemory / 16).coerceIn(8L * M, 50L * M).toInt()
}

/**
 * 内存缓存数据,防止OOM（上限动态：maxMemory/16，范围 8M..50M）
 */
private val memoryLruCache = object : LruCache<String, Any>(memoryCacheSize()) {

    override fun sizeOf(key: String, value: Any): Int {
        return value.toString().memorySize()
    }

}

object AppCacheManager {

    fun put(key: String, queryTTF: QueryTTF) {
        queryTTFMap.put(key, queryTTF)
    }

    fun getQueryTTF(key: String): QueryTTF? {
        return queryTTFMap[key]
    }

    // B13: 清空全部内存缓存
    fun clearMemory() {
        queryTTFMap.evictAll()
        memoryLruCache.evictAll()
    }

    fun clearSourceVariables() {
        memoryLruCache.snapshot().keys.forEach {
            if (it.startsWith("v_")
                || it.startsWith("userInfo_")
                || it.startsWith("loginHeader_")
                || it.startsWith("sourceVariable_")
            ) {
                memoryLruCache.remove(it)
            }
        }
    }

}


@Keep
@Suppress("unused")
object CacheManager {

    /**
     * saveTime 单位为秒
     */
    @JvmOverloads
    fun put(key: String, value: Any, saveTime: Int = 0) {
        val deadline =
            if (saveTime == 0) 0 else System.currentTimeMillis() + saveTime * 1000
        when (value) {
            is ByteArray -> ACache.get().put(key, value, saveTime)
            else -> {
                val valueStr = value.toString()
                putMemory(key, valueStr)
                val cache = Cache(key, valueStr, deadline)
                runBlocking(IO) { appDb.cacheDao.insert(cache) }
            }
        }
    }

    fun putMemory(key: String, value: Any) {
        memoryLruCache.put(key, value)
    }

    //从内存中获取数据 使用lruCache
    fun getFromMemory(key: String): Any? {
        return memoryLruCache[key]
    }

    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }

    fun get(key: String): String? {
        getFromMemory(key)?.let {
            if (it is String) return it
        }
        val cache = runBlocking(IO) { appDb.cacheDao.get(key) }
        if (cache != null && (cache.deadline == 0L || cache.deadline > System.currentTimeMillis())) {
            return cache.value?.also {
                putMemory(key, it)
            }
        }
        return null
    }

    fun get(key: String, onlyDisk: Boolean): String? {
        if (!onlyDisk) {
            return get(key)
        }
        val cache = runBlocking(IO) { appDb.cacheDao.get(key) }
        if (cache != null && (cache.deadline == 0L || cache.deadline > System.currentTimeMillis())) {
            return cache.value
        }
        return null
    }

    fun getInt(key: String): Int? {
        getFromMemory(key)?.let {
            if (it is Int) return it
        }
        return get(key, true)?.toIntOrNull()
    }

    fun getLong(key: String): Long? {
        getFromMemory(key)?.let {
            if (it is Long) return it
        }
        return get(key, true)?.toLongOrNull()
    }

    fun getDouble(key: String): Double? {
        getFromMemory(key)?.let {
            if (it is Double) return it
        }
        return get(key, true)?.toDoubleOrNull()
    }

    fun getFloat(key: String): Float? {
        getFromMemory(key)?.let {
            if (it is Float) return it
        }
        return get(key, true)?.toFloatOrNull()
    }

    fun getByteArray(key: String): ByteArray? {
        return ACache.get().getAsBinary(key)
    }

    fun putFile(key: String, value: String, saveTime: Int = 0) {
        ACache.get().put(key, value, saveTime)
    }

    fun getFile(key: String): String? {
        return ACache.get().getAsString(key)
    }

    fun delete(key: String) {
        runBlocking(IO) { appDb.cacheDao.delete(key) }
        deleteMemory(key)
        ACache.get().remove(key)
    }

    // B13: 系统内存压力时降级内存缓存
    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            AppCacheManager.clearMemory()
        } else if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        ) {
            memoryLruCache.trimToSize(memoryLruCache.maxSize() / 2)
        }
    }
}

@Keep
@Suppress("unused")
object WebCacheManager {
    @JvmOverloads
    @JavascriptInterface
    fun put(key: String, value: String, saveTime: Int = 0) {
        CacheManager.put(key, value, saveTime)
    }
    @JavascriptInterface
    fun putMemory(key: String, value: String) {
        memoryLruCache.put(key, value)
    }
    @JavascriptInterface
    fun getFromMemory(key: String): String? {
        return memoryLruCache[key]?.toString()
    }
    @JavascriptInterface
    fun deleteMemory(key: String) {
        memoryLruCache.remove(key)
    }
    @JavascriptInterface
    fun get(key: String): String? {
        return CacheManager.get(key)
    }
    @JavascriptInterface
    fun get(key: String, onlyDisk: Boolean): String? {
        return CacheManager.get(key, onlyDisk)
    }
    @JavascriptInterface
    fun putFile(key: String, value: String, saveTime: Int = 0) {
        CacheManager.putFile(key, value, saveTime)
    }
    @JavascriptInterface
    fun getFile(key: String): String? {
        return CacheManager.getFile(key)
    }
    @JavascriptInterface
    fun delete(key: String) {
        CacheManager.delete(key)
    }
}