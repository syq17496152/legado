package io.legado.app.help

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.compressPreservingAlpha
import io.legado.app.utils.preferredCoverExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object CoverThumbnailCache {

    private const val thumbWidth = 240
    private const val thumbHeight = 320
    private const val dirName = "cover_thumbs_v2"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun file(context: Context, key: String?, extension: String): File? {
        if (key.isNullOrBlank()) return null
        val dir = File(context.cacheDir, dirName)
        return File(dir, "${MD5Utils.md5Encode(key)}.$extension")
    }

    fun existing(context: Context, key: String?): File? {
        if (key.isNullOrBlank()) return null
        return listOf("png", "jpg")
            .asSequence()
            .mapNotNull { file(context, key, it) }
            .firstOrNull { it.exists() && it.length() > 0L }
    }

    fun saveAsync(context: Context, key: String?, drawable: Drawable) {
        val cacheKey = key
        if (cacheKey.isNullOrBlank()) return
        val source = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                saveBitmap(appContext, cacheKey, source)
            }
        }
    }

    fun saveBlocking(context: Context, key: String?, drawable: Drawable) {
        val cacheKey = key
        if (cacheKey.isNullOrBlank()) return
        val source = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return
        runCatching {
            saveBitmap(context.applicationContext, cacheKey, source)
        }
    }

    private fun saveBitmap(context: Context, key: String, source: Bitmap) {
        val thumb = Bitmap.createScaledBitmap(source, thumbWidth, thumbHeight, true)
        try {
            val extension = thumb.preferredCoverExtension()
            val target = file(context, key, extension) ?: return
            if (target.exists() && target.length() > 0L) return
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                thumb.compressPreservingAlpha(out, 86)
            }
        } finally {
            if (thumb !== source) thumb.recycle()
        }
    }
}