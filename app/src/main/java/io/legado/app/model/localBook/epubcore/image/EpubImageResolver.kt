package io.legado.app.model.localBook.epubcore.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import android.util.LruCache
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.utils.MAX_DATA_URL_BYTES
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.decodeBase64DataUrlBytes
import java.io.ByteArrayInputStream

class EpubImageResolver(
    private val archive: EpubArchive,
    maxCacheBytes: Int = (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(4 * 1024 * 1024)
) {

    private val bitmapCache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (!oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    private val metaCache = hashMapOf<String, EpubImageMeta?>()

    data class EpubImageMeta(
        val width: Int,
        val height: Int,
        val svg: Boolean
    )

    @Synchronized
    fun probe(href: String): EpubImageMeta? {
        if (metaCache.containsKey(href)) return metaCache[href]
        val bytes = readBytes(href)
        val meta = bytes?.let { data ->
            runCatching {
                if (isSvg(href, data)) {
                    SvgUtils.getSize(ByteArrayInputStream(data))?.toMeta(svg = true)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        EpubImageMeta(bounds.outWidth, bounds.outHeight, svg = false)
                    } else {
                        null
                    }
                }
            }.onFailure(::handleDecodeFailure).getOrNull()
        }
        metaCache[href] = meta
        return meta
    }

    fun decode(href: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        val key = "${href}|${width}x$height"
        bitmapCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }
        val bytes = readBytes(href) ?: return null
        return runCatching {
            val bitmap = if (isSvg(href, bytes)) {
                SvgUtils.createBitmap(ByteArrayInputStream(bytes), width, height)
            } else {
                decodeBitmap(bytes, width, height)
            } ?: return null
            if (bitmap.allocationByteCount <= bitmapCache.maxSize()) {
                bitmapCache.put(key, bitmap)
            }
            bitmap
        }.onFailure(::handleDecodeFailure).getOrNull()
    }

    fun clear() {
        bitmapCache.evictAll()
        metaCache.clear()
    }

    private fun readBytes(href: String): ByteArray? {
        return if (href.startsWith("data:", ignoreCase = true)) {
            runCatching { decodeDataUrl(href) }
                .onFailure(::handleDecodeFailure)
                .getOrNull()
        } else {
            runCatching {
                archive.readBytes(EpubPath.stripFragment(href), EpubArchive.DEFAULT_MAX_ENTRY_BYTES)
            }.getOrNull()
        }
    }

    private fun handleDecodeFailure(error: Throwable) {
        if (error is OutOfMemoryError) {
            bitmapCache.evictAll()
        }
    }

    private fun decodeBitmap(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateSampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun isSvg(href: String, bytes: ByteArray): Boolean {
        if (href.substringBefore('?').endsWith(".svg", ignoreCase = true)) return true
        val head = bytes.decodeToString(endIndex = bytes.size.coerceAtMost(256)).trimStart()
        return head.startsWith("<svg", ignoreCase = true) || head.contains("<svg", ignoreCase = true)
    }

    private fun decodeDataUrl(href: String): ByteArray? {
        val comma = href.indexOf(',')
        if (comma < 0) return null
        val meta = href.substring(0, comma)
        val payload = href.substring(comma + 1)
        return if (meta.contains(";base64", ignoreCase = true)) {
            href.decodeBase64DataUrlBytes(EpubArchive.DEFAULT_MAX_ENTRY_BYTES)
        } else {
            runCatching {
                java.net.URLDecoder.decode(payload, Charsets.UTF_8.name())
                    .toByteArray()
                    .takeIf { it.size <= MAX_DATA_URL_BYTES }
            }.getOrNull()
        }
    }

    private fun Size.toMeta(svg: Boolean): EpubImageMeta {
        return EpubImageMeta(width.coerceAtLeast(1), height.coerceAtLeast(1), svg)
    }
}
