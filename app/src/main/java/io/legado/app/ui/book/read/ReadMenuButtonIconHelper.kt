package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.normalizeFileName
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object ReadMenuButtonIconHelper {

    fun drawable(
        context: Context,
        ref: ReadMenuButtonConfig.ButtonRef,
        @DrawableRes fallbackRes: Int,
        customButtonIconPath: String? = null,
        targetSize: Int = 96
    ): Drawable? {
        val path = iconPath(ref, customButtonIconPath)
        return loadDrawable(context, path, targetSize)
            ?: ContextCompat.getDrawable(context, fallbackRes)
    }

    fun saveIcon(context: Context, uri: Uri, oldPath: String? = null, targetSize: Int = 96): String {
        val dir = iconDir(context).apply { mkdirs() }
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 2..5 }
            ?: "png"
        val name = "icon_${System.currentTimeMillis()}.$extension".normalizeFileName()
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Icon file read failed")
        if (loadDrawable(context, target.absolutePath, targetSize) == null) {
            target.delete()
            throw IllegalArgumentException("Icon parse failed")
        }
        clearIcon(oldPath)
        return target.absolutePath
    }

    fun clearIcon(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            val iconDir = iconDir(appCtx).canonicalFile
            val target = file.canonicalFile
            if (target.parentFile == iconDir && target.exists() && target.isFile) {
                target.delete()
            }
        }
    }

    private fun iconPath(
        ref: ReadMenuButtonConfig.ButtonRef,
        customButtonIconPath: String?
    ): String? {
        if (ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
            ref.id == ReadMenuButtonConfig.Builtin.NIGHT_THEME &&
            AppConfig.isNightTheme
        ) {
            ref.nightIconPath.takeIf { it.isNotBlank() }?.let { return it }
        }
        ref.iconPath.takeIf { it.isNotBlank() }?.let { return it }
        if (ref.type == ReadMenuButtonConfig.TYPE_CUSTOM) {
            customButtonIconPath?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun loadDrawable(context: Context, path: String?, targetSize: Int): Drawable? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        val bitmap = if (file.extension.equals("svg", ignoreCase = true)) {
            SvgUtils.createBitmap(file.absolutePath, targetSize, targetSize)
        } else {
            decodeBitmapFile(file, targetSize)
                ?: SvgUtils.createBitmap(file.absolutePath, targetSize, targetSize)
        } ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun iconDir(context: Context): File {
        return File(context.externalFiles, "readMenuIcons")
    }

    private fun decodeBitmapFile(file: File, targetSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val target = targetSize.coerceAtLeast(1)
        var sampleSize = 1
        var halfWidth = options.outWidth / 2
        var halfHeight = options.outHeight / 2
        while (halfWidth / sampleSize >= target && halfHeight / sampleSize >= target) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
        )
    }
}
