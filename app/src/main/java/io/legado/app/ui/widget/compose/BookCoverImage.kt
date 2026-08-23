package io.legado.app.ui.widget.compose

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF as AndroidRectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.CoverThumbnailCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.help.config.CoverCollectionManager.isRealCoverPath
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import kotlin.math.roundToInt

private const val BOOK_COVER_ASPECT_RATIO = 0.75f
private const val COVER_THUMB_WIDTH = 240
private const val COVER_THUMB_HEIGHT = 320

private var cachedDefaultDrawable: Drawable? = null
private var cachedDefaultBitmap: Bitmap? = null

@Composable
fun BookCoverImage(
    book: Book,
    modifier: Modifier = Modifier,
    style: CoverImageView.CoverStyle = CoverImageView.CoverStyle.LIST,
    loadOnlyWifi: Boolean = AppConfig.loadCoverOnlyWifi,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    preferThumb: Boolean = true,
    fillBounds: Boolean = false,
    onBoundsChanged: ((Rect) -> Unit)? = null
) {
    val display = remember(
        book.bookUrl,
        book.getDisplayCover(),
        book.name,
        book.author,
        book.origin,
        AppConfig.useDefaultCover,
        CoverCollectionManager.selectionKey()
    ) {
        CoverDisplayResolver.resolve(book)
    }
    BookCoverImage(
        path = display.path,
        name = display.name,
        author = display.author,
        sourceOrigin = display.sourceOrigin,
        modifier = modifier,
        style = style,
        loadOnlyWifi = loadOnlyWifi,
        fragment = fragment,
        lifecycle = lifecycle,
        preferThumb = preferThumb,
        forcePath = display.forcePath,
        allowNameOverlay = display.allowNameOverlay,
        fillBounds = fillBounds,
        onBoundsChanged = onBoundsChanged
    )
}

@Composable
fun BookCoverImage(
    book: SearchBook,
    modifier: Modifier = Modifier,
    style: CoverImageView.CoverStyle = CoverImageView.CoverStyle.LIST,
    loadOnlyWifi: Boolean = AppConfig.loadCoverOnlyWifi,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    preferThumb: Boolean = true,
    fillBounds: Boolean = false,
    onBoundsChanged: ((Rect) -> Unit)? = null
) {
    val display = remember(
        book.bookUrl,
        book.coverUrl,
        book.name,
        book.author,
        book.origin,
        AppConfig.useDefaultCover,
        CoverCollectionManager.selectionKey()
    ) {
        CoverDisplayResolver.resolve(book)
    }
    BookCoverImage(
        path = display.path,
        name = display.name,
        author = display.author,
        sourceOrigin = display.sourceOrigin,
        modifier = modifier,
        style = style,
        loadOnlyWifi = loadOnlyWifi,
        fragment = fragment,
        lifecycle = lifecycle,
        preferThumb = preferThumb,
        forcePath = display.forcePath,
        allowNameOverlay = display.allowNameOverlay,
        fillBounds = fillBounds,
        onBoundsChanged = onBoundsChanged
    )
}

@Composable
fun BookCoverImage(
    path: String?,
    name: String?,
    author: String?,
    sourceOrigin: String?,
    modifier: Modifier = Modifier,
    style: CoverImageView.CoverStyle = CoverImageView.CoverStyle.LIST,
    loadOnlyWifi: Boolean = AppConfig.loadCoverOnlyWifi,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    preferThumb: Boolean = true,
    forcePath: Boolean = false,
    allowNameOverlay: Boolean? = null,
    fillBounds: Boolean = false,
    onBoundsChanged: ((Rect) -> Unit)? = null
) {
    val context = LocalContext.current
    val defaultBitmap = rememberDefaultCoverBitmap()
    val cleanName = remember(name) { name?.replace(AppPattern.bdRegex, "")?.trim() }
    val cleanAuthor = remember(author) { author?.replace(AppPattern.bdRegex, "")?.trim() }
    val useThumb = preferThumb && !AppConfig.loadCoverHighQuality
    val hasRealCover = remember(path) { path.isRealCoverPath() }
    val drawNameOverlay = allowNameOverlay
        ?: ((AppConfig.useDefaultCover && !forcePath) || !hasRealCover)
    val thumbKey = remember(sourceOrigin, path, cleanName, cleanAuthor) {
        "$sourceOrigin|$path|$cleanName|$cleanAuthor"
    }
    val loadKey = remember(
        path,
        cleanName,
        cleanAuthor,
        sourceOrigin,
        loadOnlyWifi,
        AppConfig.useDefaultCover,
        AppConfig.loadCoverHighQuality,
        CoverCollectionManager.selectionKey(),
        useThumb,
        forcePath,
        allowNameOverlay,
        System.identityHashCode(BookCover.defaultDrawable)
    ) {
        listOf(
            path.orEmpty(),
            cleanName.orEmpty(),
            cleanAuthor.orEmpty(),
            sourceOrigin.orEmpty(),
            loadOnlyWifi.toString(),
            AppConfig.useDefaultCover.toString(),
            AppConfig.loadCoverHighQuality.toString(),
            CoverCollectionManager.selectionKey(),
            useThumb.toString(),
            forcePath.toString(),
            allowNameOverlay.toString(),
            System.identityHashCode(BookCover.defaultDrawable).toString()
        ).joinToString("|")
    }
    var bitmap by remember(loadKey, defaultBitmap) { mutableStateOf(defaultBitmap) }

    DisposableEffect(loadKey, context, fragment, lifecycle) {
        var active = true
        bitmap = defaultBitmap
        if (!(AppConfig.useDefaultCover && !forcePath)) {
            val thumbFile = if (useThumb) CoverThumbnailCache.existing(context, thumbKey) else null
            val target = object : CustomTarget<Bitmap>() {
                override fun onLoadStarted(placeholder: Drawable?) {
                    if (active) {
                        bitmap = placeholder.toCoverBitmap(defaultBitmap)
                    }
                }

                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (active && !resource.isRecycled) {
                        bitmap = resource
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (active) {
                        bitmap = errorDrawable.toCoverBitmap(defaultBitmap)
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (active) {
                        bitmap = placeholder.toCoverBitmap(defaultBitmap)
                    }
                }
            }
            var options = RequestOptions()
                .format(DecodeFormat.PREFER_ARGB_8888)
                .disallowHardwareConfig()
                .set(OkHttpModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            if (sourceOrigin != null) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
            }
            val builder = when {
                thumbFile != null -> ImageLoader.loadBitmap(context, thumbFile.absolutePath)
                fragment != null && lifecycle != null -> runCatching {
                    ImageLoader.loadBitmap(fragment, lifecycle, path)
                }.getOrElse {
                    ImageLoader.loadBitmap(context, path)
                }
                else -> ImageLoader.loadBitmap(context, path)
            }
            builder
                .apply(options)
                .let { if (thumbFile == null) it.placeholder(BookCover.defaultDrawable) else it }
                .error(BookCover.defaultDrawable)
                .priority(if (useThumb) Priority.HIGH else Priority.NORMAL)
                .override(
                    if (useThumb) COVER_THUMB_WIDTH else Target.SIZE_ORIGINAL,
                    if (useThumb) COVER_THUMB_HEIGHT else Target.SIZE_ORIGINAL
                )
                .centerCrop()
                .addListener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: Target<Bitmap>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        if (useThumb && thumbFile == null) {
                            CoverThumbnailCache.saveAsync(
                                context,
                                thumbKey,
                                BitmapDrawable(context.resources, resource)
                            )
                        }
                        return false
                    }
                })
                .into(target)
            onDispose {
                active = false
                runCatching { Glide.with(context.applicationContext).clear(target) }
            }
        } else {
            onDispose {
                active = false
            }
        }
    }

    val currentOnBoundsChanged by rememberUpdatedState(onBoundsChanged)
    val themeSignature = rememberThemeUiPalette().signature
    val coverShadowEnabled = remember(themeSignature) { AppConfig.bookCoverShadow }
    val shape = RoundedCornerShape(style.radiusDp.dp)
    val frameModifier = modifier
        .then(if (fillBounds) Modifier else Modifier.aspectRatio(BOOK_COVER_ASPECT_RATIO))
        .then(
            if (currentOnBoundsChanged != null) {
                Modifier.onGloballyPositioned { coordinates ->
                    currentOnBoundsChanged?.invoke(coordinates.boundsInRoot())
                }
            } else {
                Modifier
            }
        )
        .coverOuterShadow(style, coverShadowEnabled)
        .clip(shape)

    Box(modifier = frameModifier) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (drawNameOverlay && BookCover.drawBookName && !cleanName.isNullOrBlank()) {
            BookCoverNameOverlay(
                name = cleanName,
                author = cleanAuthor,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

private fun Modifier.coverOuterShadow(
    style: CoverImageView.CoverStyle,
    enabled: Boolean
): Modifier {
    if (!enabled || style.elevationDp <= 0f) return this
    return drawWithCache {
        val radius = style.radiusDp.dp.toPx()
        val blurRadius = maxOf(1f, style.elevationDp.dp.toPx() * 1.35f)
        val spread = blurRadius * 2.5f
        val shadowAlpha = (0.10f + style.elevationDp * 0.018f).coerceIn(0.12f, 0.22f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb((shadowAlpha * 255).roundToInt(), 0, 0, 0)
            maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val coverRect = AndroidRectF(0f, 0f, size.width, size.height)
        val layerRect = AndroidRectF(-spread, -spread, size.width + spread, size.height + spread)
        onDrawBehind {
            drawContext.canvas.nativeCanvas.apply {
                val saveCount = saveLayer(layerRect, null)
                drawRoundRect(coverRect, radius, radius, shadowPaint)
                drawRoundRect(coverRect, radius, radius, clearPaint)
                restoreToCount(saveCount)
            }
        }
    }
}

@Composable
private fun BookCoverNameOverlay(
    name: String,
    author: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeSignature = rememberThemeUiPalette().signature
    val backgroundColor = remember(context, themeSignature) { context.backgroundColor }
    val nameColor = remember(context, themeSignature) { context.titleTextColor }
    val authorColor = remember(context, themeSignature) { context.secondaryTextColor }
    Canvas(modifier = modifier) {
        val viewWidth = size.width
        val viewHeight = size.height
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas
        var startX = viewWidth * 0.2f
        var startY = viewHeight * 0.2f
        val namePaint = TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        name.toStringArray().let { chars ->
            var line = 0
            namePaint.textSize = viewWidth / 7f
            namePaint.strokeWidth = namePaint.textSize / 6f
            chars.forEachIndexed { index, char ->
                namePaint.color = backgroundColor
                namePaint.style = Paint.Style.STROKE
                drawContext.canvas.nativeCanvas.drawText(char, startX, startY, namePaint)
                namePaint.color = nameColor
                namePaint.style = Paint.Style.FILL
                drawContext.canvas.nativeCanvas.drawText(char, startX, startY, namePaint)
                startY += namePaint.textHeight
                if (startY > viewHeight * 0.9f) {
                    if (chars.size - index - 1 == 1) {
                        startY -= namePaint.textHeight / 5f
                        namePaint.textSize = viewWidth / 9f
                        return@forEachIndexed
                    }
                    startX += namePaint.textSize
                    line++
                    namePaint.textSize = viewWidth / 10f
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                } else if (startY > viewHeight * 0.8f && chars.size - index - 1 > 2) {
                    startX += namePaint.textSize
                    line++
                    namePaint.textSize = viewWidth / 10f
                    startY = viewHeight * 0.2f + namePaint.textHeight * line
                }
            }
        }
        if (!BookCover.drawBookAuthor) return@Canvas
        val authorPaint = TextPaint(namePaint).apply {
            typeface = Typeface.DEFAULT
        }
        author?.toStringArray()?.let { chars ->
            authorPaint.textSize = viewWidth / 10f
            authorPaint.strokeWidth = authorPaint.textSize / 5f
            startX = viewWidth * 0.8f
            startY = viewHeight * 0.95f - chars.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            chars.forEach { char ->
                authorPaint.color = backgroundColor
                authorPaint.style = Paint.Style.STROKE
                drawContext.canvas.nativeCanvas.drawText(char, startX, startY, authorPaint)
                authorPaint.color = authorColor
                authorPaint.style = Paint.Style.FILL
                drawContext.canvas.nativeCanvas.drawText(char, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95f) {
                    return@let
                }
            }
        }
    }
}

@Composable
private fun rememberDefaultCoverBitmap(): Bitmap {
    val drawable = BookCover.defaultDrawable
    return remember(drawable) {
        defaultCoverBitmap(drawable)
    }
}

private fun defaultCoverBitmap(drawable: Drawable): Bitmap {
    cachedDefaultBitmap?.takeIf {
        cachedDefaultDrawable === drawable && !it.isRecycled
    }?.let {
        return it
    }
    return drawable.toBitmap(width = COVER_THUMB_WIDTH, height = COVER_THUMB_HEIGHT).also {
        cachedDefaultDrawable = drawable
        cachedDefaultBitmap = it
    }
}

private fun Drawable?.toCoverBitmap(fallback: Bitmap): Bitmap {
    val drawable = this ?: return fallback
    return (drawable as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }
        ?: runCatching {
            drawable.toBitmap(width = COVER_THUMB_WIDTH, height = COVER_THUMB_HEIGHT)
        }.getOrDefault(fallback)
}
