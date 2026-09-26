package io.legado.app.help

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Html
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.lang.ref.WeakReference
import io.legado.app.utils.lifecycle
import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64
import io.legado.app.utils.SvgUtils
import java.util.concurrent.ConcurrentHashMap
import androidx.core.graphics.drawable.toDrawable
import android.graphics.Color
import com.bumptech.glide.request.RequestOptions
import io.legado.app.data.appDb
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.analyzeRule.AnalyzeUrl

class GlideImageGetter(
    context: Context,
    textView: TextView,
    private val lifecycle: Lifecycle,
    private val availableWidth: Int,
    private val sourceOrigin: String? = null
) : Html.ImageGetter, Drawable.Callback {
    private val textViewRef = WeakReference(textView)
    private val contextRef = WeakReference(context)
    private val cacheDrawable = ConcurrentHashMap<String, GlideUrlDrawable>()
    private val pendingImages = mutableSetOf<String>()
    private val emptyDrawable by lazy {
        Color.TRANSPARENT.toDrawable()
    }
    private val bookSource by lazy {
        sourceOrigin?.let { appDb.bookSourceDao.getBookSource(it) }
    }

    override fun getDrawable(source: String?): Drawable {
        val context = contextRef.get()
        if (context == null || source.isNullOrBlank()) {
            return emptyDrawable
        }
        // Q3：源参数解析统一走 ImageSourceOptions（上游移植版，支持 HTML 实体/非字符串参数/大小写不敏感）
        val parsedSource = ImageSourceOptions.parse(source)
        val imageSource = parsedSource?.source ?: source
        if (imageSource.startsWith("data:", ignoreCase = true)) {
            // 保留 SVG 的 XML 原文；栅格 data URI 仍按书源规则解析 URL（保持一致的书源头/解密链路）
            val isSvg = imageSource.substringBefore(",").contains("image/svg", ignoreCase = true)
            val normalizedDataSource = if (isSvg) {
                imageSource
            } else {
                runCatching {
                    AnalyzeUrl(imageSource, source = bookSource).url
                }.getOrDefault(imageSource)
            }
            val bytes = decodeDataUri(normalizedDataSource) ?: return emptyDrawable
            if (isSvg) {
                val (pictureDrawable, size) = SvgUtils.createDrawable(ByteArrayInputStream(bytes))
                    ?: return emptyDrawable
                pictureDrawable.bounds = getDrawableRect(parsedSource, size.width, size.height)
                return pictureDrawable
            }
            // 栅格 data URI 原实现走 SVG 解析 ⇒ 必然解不出、静默空白；此处按位图渲染（上游口径）
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return emptyDrawable
            val drawable = BitmapDrawable(context.resources, bitmap)
            drawable.bounds = getDrawableRect(parsedSource, bitmap.width, bitmap.height)
            return drawable
        }
        cacheDrawable[source]?.let {
            return it
        }
        val urlDrawable = GlideUrlDrawable()
        cacheDrawable[source] = urlDrawable
        pendingImages.add(source)
        val target = ImageTarget(urlDrawable, source, parsedSource)
        var options = RequestOptions()
        if (sourceOrigin != null) {
            options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
        }
        // 保留带参数段的原始 source 交给 AnalyzeUrl：书源头/解密链路依赖它
        Glide.with(context).lifecycle(lifecycle)
            .load(source)
            .apply(options)
            .into(target)
        return urlDrawable
    }

    /**
     * data URI 安全解码：base64（含 URL-safe 变体与缺省填充）优先，否则按 URI 百分号解码。
     * 原实现直接 `Base64.decode` 且**无异常保护** ⇒ 正文里出现畸形 data URI 会在 TextView 布局期崩溃。
     */
    private fun decodeDataUri(source: String): ByteArray? {
        val separator = source.indexOf(',')
        if (separator < 0) return null
        val metadata = source.substring(0, separator)
        val payload = source.substring(separator + 1)
        return if (metadata.contains(";base64", ignoreCase = true)) {
            val normalized = payload
                .filterNot(Char::isWhitespace)
                .replace('-', '+')
                .replace('_', '/')
                .let { value -> value.padEnd((value.length + 3) / 4 * 4, '=') }
            runCatching { Base64.decode(normalized) }.getOrNull()
        } else {
            runCatching { Uri.decode(payload).toByteArray(Charsets.UTF_8) }.getOrNull()
        }
    }

    /** 把纯函数求解出的 [ImageBounds] 落到 Android 的 `Rect`（唯一的平台适配点） */
    private fun getDrawableRect(
        parsed: ParsedImageSource?,
        intrinsicWidth: Int,
        intrinsicHeight: Int
    ): Rect {
        val bounds = resolveImageBounds(parsed, intrinsicWidth, intrinsicHeight, availableWidth)
        return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private fun notifyImageLoaded(source: String) {
        pendingImages.remove(source)
        if (pendingImages.isEmpty()) {
            val textView = textViewRef.get() ?: return
            textView.text = textView.text
        }
    }

    override fun invalidateDrawable(who: Drawable) {
        textViewRef.get()?.invalidate()
    }

    override fun scheduleDrawable(
        who: Drawable,
        what: Runnable,
        `when`: Long
    ) {
    }

    override fun unscheduleDrawable(
        who: Drawable,
        what: Runnable
    ) {
    }

    private inner class GlideUrlDrawable() : Drawable(), Animatable {
        private var mDrawable: Drawable? = null
        private var gDrawable: GifDrawable? = null

        fun setDrawable(drawable: Drawable?) {
            if (drawable is GifDrawable) {
                gDrawable?.apply {
                    callback = null
                    stop()
                }
                gDrawable = drawable.apply {
                    if (!isRunning) {
                        callback = this@GlideImageGetter
                        start()
                    }
                }
                mDrawable = null
            } else {
                gDrawable?.apply {
                    callback = null
                    stop()
                }
                gDrawable = null
                mDrawable = drawable
            }
        }

        fun clear() {
            gDrawable?.apply {
                callback = null
                stop()
            }
            gDrawable = null
            mDrawable = null
        }

        override fun draw(canvas: Canvas) {
            (mDrawable ?: gDrawable)?.draw(canvas)
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            (mDrawable ?: gDrawable)?.bounds = bounds
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            val drawable = mDrawable ?: gDrawable
            return if (drawable != null) {
                when {
                    drawable.alpha == 0 -> PixelFormat.TRANSPARENT
                    drawable.alpha == 255 -> PixelFormat.OPAQUE
                    else -> PixelFormat.TRANSLUCENT
                }
            } else {
                PixelFormat.TRANSLUCENT
            }
        }

        override fun setAlpha(alpha: Int) {
            mDrawable?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            mDrawable?.colorFilter = colorFilter
        }

        override fun getIntrinsicWidth(): Int {
            return (mDrawable ?: gDrawable)?.intrinsicWidth ?: 0
        }

        override fun getIntrinsicHeight(): Int {
            return (mDrawable ?: gDrawable)?.intrinsicHeight ?: 0
        }

        override fun isRunning(): Boolean {
            return gDrawable?.isRunning == true
        }

        override fun start() {
            gDrawable?.start()
        }

        override fun stop() {
            gDrawable?.stop()
        }
    }

    private inner class ImageTarget(
        private val urlDrawable: GlideUrlDrawable,
        private val source: String,
        private val parsedSource: ParsedImageSource?
    ) : CustomTarget<Drawable>() {

        override fun onResourceReady(
            drawable: Drawable,
            transition: Transition<in Drawable>?
        ) {
            urlDrawable.setDrawable(drawable)
            urlDrawable.bounds =
                getDrawableRect(parsedSource, drawable.intrinsicWidth, drawable.intrinsicHeight)
            notifyImageLoaded(source)
        }

        override fun onLoadCleared(placeholder: Drawable?) {
            urlDrawable.clear()
            cacheDrawable.remove(source)
        }

        override fun onLoadFailed(errorDrawable: Drawable?) {
            urlDrawable.setDrawable(errorDrawable)
            notifyImageLoaded(source)
        }
    }

    fun start() {
        cacheDrawable.values.forEach { drawable ->
            drawable.start()
        }
    }

    fun stop() {
        cacheDrawable.values.forEach { drawable ->
            drawable.stop()
        }
    }

    fun clear() {
        cacheDrawable.values.forEach { drawable ->
            drawable.clear()
        }
        cacheDrawable.clear()
        contextRef.clear()
        textViewRef.clear()
    }
}