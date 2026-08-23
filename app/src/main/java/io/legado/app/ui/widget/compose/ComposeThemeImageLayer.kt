package io.legado.app.ui.widget.compose

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.widget.AppCompatImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.utils.ImageTypeUtils
import java.io.File

@Composable
fun ComposeThemeImageLayer(
    state: ComposeThemeImageState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    stableWidthScale: Boolean = false
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(Color(state.fallbackColor))
    ) {
        if (state.file != null) {
            ComposeThemeImage(
                file = state.file,
                animate = state.animated,
                alpha = state.alpha,
                crop = state.crop,
                stableWidthScale = stableWidthScale
            )
        }
    }
}

@Composable
private fun ComposeThemeImage(
    file: File,
    animate: Boolean,
    alpha: Float,
    crop: ComposeThemeImageCrop?,
    stableWidthScale: Boolean
) {
    // Include file stamp so same-path wallpaper replacements bypass stale Glide cache.
    val fileLength = file.length()
    val fileLastModified = file.lastModified()
    val loadKey = remember(file.absolutePath, fileLength, fileLastModified, animate, crop) {
        ComposeThemeImageLoadKey(file.absolutePath, fileLength, fileLastModified, animate, crop)
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            CropAwareImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { imageView ->
            imageView.alpha = alpha.coerceIn(0f, 1f)
            imageView.stableWidthScale = stableWidthScale
            imageView.crop = crop
            if (imageView.loadKey == loadKey) {
                (imageView.drawable as? Animatable)?.start()
                return@AndroidView
            }
            imageView.loadKey = loadKey
            val requestManager = Glide.with(imageView.context.applicationContext ?: imageView.context)
            if (crop != null || stableWidthScale) {
                imageView.scaleType = ImageView.ScaleType.MATRIX
                if (animate) {
                    requestManager.load(file)
                        .signature(ObjectKey(loadKey.cacheKey))
                        .listener(imageView.drawableStartListener())
                        .into(imageView)
                } else {
                    requestManager.asBitmap().load(file)
                        .signature(ObjectKey(loadKey.cacheKey))
                        .into(imageView)
                }
            } else {
                imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                if (animate) {
                    requestManager.load(file).centerCrop()
                        .signature(ObjectKey(loadKey.cacheKey))
                        .listener(imageView.drawableStartListener())
                        .into(imageView)
                } else {
                    requestManager.asBitmap().load(file).centerCrop()
                        .signature(ObjectKey(loadKey.cacheKey))
                        .into(imageView)
                }
            }
        },
        onRelease = { imageView ->
            // 离开 composition 时停止动画并取消/释放 Glide，防止 gif/webp 持续解码与 bitmap 泄漏
            imageView.releaseComposeImage()
            imageView.loadKey = null
        }
    )
}

private class CropAwareImageView(context: Context) : AppCompatImageView(context) {

    var loadKey: ComposeThemeImageLoadKey? = null
    var stableWidthScale: Boolean = false
        set(value) {
            field = value
            applyCropMatrixIfNeeded()
        }

    var crop: ComposeThemeImageCrop? = null
        set(value) {
            field = value
            applyCropMatrixIfNeeded()
        }

    fun drawableStartListener(): RequestListener<Drawable> {
        return object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean = false

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                post {
                    applyCropMatrixIfNeeded()
                    (drawable as? Animatable)?.start()
                    (resource as? Animatable)?.start()
                }
                return false
            }
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { applyCropMatrixIfNeeded() }
        (drawable as? Animatable)?.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? Animatable)?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCropMatrixIfNeeded()
    }

    private fun applyCropMatrixIfNeeded() {
        val crop = crop
        if (crop == null && !stableWidthScale) return
        if (scaleType != ImageView.ScaleType.MATRIX) return
        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
        val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
        val viewWidth = width.takeIf { it > 0 } ?: return
        val viewHeight = height.takeIf { it > 0 } ?: return
        val cropRect = crop?.toRect(drawableWidth.toFloat(), drawableHeight.toFloat())
            ?: RectF(0f, 0f, drawableWidth.toFloat(), drawableHeight.toFloat())
        val widthScale = viewWidth / cropRect.width()
        val scale = if (stableWidthScale) {
            val heightScale = if (cropRect.height() * widthScale < viewHeight) {
                viewHeight / cropRect.height()
            } else {
                0f
            }
            maxOf(widthScale, heightScale)
        } else {
            maxOf(widthScale, viewHeight / cropRect.height())
        }
        val dx = -cropRect.left * scale + (viewWidth - cropRect.width() * scale) / 2f
        val dy = if (stableWidthScale) {
            -cropRect.top * scale
        } else {
            -cropRect.top * scale + (viewHeight - cropRect.height() * scale) / 2f
        }
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}

data class ComposeThemeImageState(
    val file: File?,
    val animated: Boolean = ImageTypeUtils.isAnimatedImage(file),
    val alpha: Float = 1f,
    val crop: ComposeThemeImageCrop? = null,
    val fallbackColor: Int
)

private data class ComposeThemeImageLoadKey(
    val path: String,
    val length: Long,
    val lastModified: Long,
    val animated: Boolean,
    val crop: ComposeThemeImageCrop?
) {
    val cacheKey: String = "$path:$length:$lastModified:$animated:$crop"
}

data class ComposeThemeImageCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRect(width: Float, height: Float): RectF? {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        if (safeRight <= safeLeft || safeBottom <= safeTop) return null
        return RectF(
            safeLeft * width,
            safeTop * height,
            safeRight * width,
            safeBottom * height
        )
    }
}
