package io.legado.app.ui.book.read.page

import android.graphics.Bitmap
import android.util.LruCache

internal object LottieImageBitmapCache {
    private val maxBytes = LottieImageMemoryPolicy.cacheBudgetBytes(Runtime.getRuntime().maxMemory())
    private val cache = object : LruCache<LottieImageCacheKey, Bitmap>(maxBytes) {
        override fun sizeOf(key: LottieImageCacheKey, value: Bitmap): Int {
            return LottieImageMemoryPolicy.chargeBytes(
                allocationBytes = runCatching { value.allocationByteCount }.getOrDefault(0),
                rowBytes = value.rowBytes,
                height = value.height
            )
        }
    }

    fun get(key: LottieImageCacheKey): Bitmap? = cache.get(key)?.takeUnless { it.isRecycled }

    fun put(key: LottieImageCacheKey, bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmapByteCount(bitmap) <= maxBytes) {
            cache.put(key, bitmap)
        }
    }

    fun trimMemory() {
        cache.trimToSize(maxBytes / 2)
    }

    fun clear() {
        cache.evictAll()
    }

    private fun bitmapByteCount(bitmap: Bitmap): Int {
        return LottieImageMemoryPolicy.chargeBytes(
            allocationBytes = runCatching { bitmap.allocationByteCount }.getOrDefault(0),
            rowBytes = bitmap.rowBytes,
            height = bitmap.height
        )
    }
}
