package io.legado.app.ui.rss.article.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 单行按需取图（design-b3-d4-flagship §2.4，替代 BaseRssArticlesAdapter.loadArticleImage）：
 * 1. 先查 DB 单行 image（RssArticleDao.getImage，单行远小于 CursorWindow 2MB，安全；
 *    flowByOriginSort 不 select image 字段的红线不得在列表流恢复）；
 * 2. item.link 复用错位防护在 Compose 下由 items key 天然保证，无需 itemView.tag 等价物；
 * 3. 失败/缺失时按 hideWhenBlank 决定隐藏或占位。
 */
@Composable
fun rememberRssArticleCoverUrl(article: RssArticle): String? {
    var url by remember(article.origin, article.link) { mutableStateOf<String?>(null) }
    LaunchedEffect(article.origin, article.link) {
        url = kotlin.runCatching {
            appDb.rssArticleDao.getImage(article.origin, article.link)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    return url
}

/**
 * 列表/网格封面（design-b3-d4-flagship §2.4）：
 * hideWhenBlank=true（ListRow，原 hideWhenBlank=true 语义）图缺失/失败即隐藏；
 * hideWhenBlank=false（GridCell，二代行为）占位常显。
 */
@Composable
fun RssArticleCover(
    article: RssArticle,
    hideWhenBlank: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val coverUrl = rememberRssArticleCoverUrl(article)
    if (coverUrl.isNullOrBlank()) {
        if (!hideWhenBlank) CoverPlaceholder(modifier = modifier, contentScale = contentScale)
        return
    }
    var drawable by remember(coverUrl) { mutableStateOf<Drawable?>(null) }
    var measuredSize by remember { mutableStateOf<IntSize?>(null) }
    // 固定尺寸容器等待首帧测量后按 override 尺寸解码（避免 SIZE_ORIGINAL 全尺寸解码）
    CoverRequestEffect(
        url = coverUrl,
        origin = article.origin,
        overrideSize = measuredSize,
        waitForSize = true,
        onLoaded = { drawable = it },
    )
    Box(modifier = modifier.onSizeChanged { measuredSize = it }) {
        when {
            drawable != null -> Image(
                painter = remember(drawable) { DrawableCoverPainter(drawable!!) },
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
            !hideWhenBlank -> CoverPlaceholder(
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
            // hideWhenBlank 且加载中/失败：空白占位（等效原 imageView.gone() 的视觉语义）
        }
    }
}

/**
 * 瀑布流封面（design-b3-d4-flagship §2.4）：缓存命中则预置高度；
 * 加载成功后记录真实宽高比（对齐三代 onResourceReady），不 override 尺寸
 * （等效原 WRAP_CONTENT + adjustViewBounds 行为）。
 */
@Composable
internal fun MasonryCover(article: RssArticle, modifier: Modifier = Modifier) {
    val coverUrl = rememberRssArticleCoverUrl(article)
    var ratio by remember(coverUrl) {
        mutableStateOf(coverUrl?.let { RssImageAspectRatioCache.get(it) } ?: 0f)
    }
    if (coverUrl.isNullOrBlank()) return
    var drawable by remember(coverUrl) { mutableStateOf<Drawable?>(null) }
    CoverRequestEffect(
        url = coverUrl,
        origin = article.origin,
        overrideSize = null,
        waitForSize = false,
        onLoaded = { resource ->
            drawable = resource
            val width = resource.intrinsicWidth
            val height = resource.intrinsicHeight
            if (width > 0 && height > 0) {
                ratio = height.toFloat() / width.toFloat()
                RssImageAspectRatioCache.put(coverUrl, ratio)
            }
        },
    )
    if (drawable != null) {
        Image(
            painter = remember(drawable) { DrawableCoverPainter(drawable!!) },
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.then(
                if (ratio > 0f) Modifier.aspectRatio(1f / ratio) else Modifier
            ),
        )
    }
}

/** 占位图（painterResource，design §2.4 PlaceholderBox 语义） */
@Composable
private fun CoverPlaceholder(modifier: Modifier, contentScale: ContentScale) {
    Image(
        painter = painterResource(R.drawable.image_rss_article),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/**
 * Glide 请求 effect 桥（原 loadArticleImage/Adapter3 的 Coroutine.async+into 语义移植）：
 * waitForSize=true 时等首帧测量后 override 解码；false 时原图解码（瀑布流）。
 * 协程取消即 Glide.clear(target)，等效原请求生命周期绑定。
 */
@Composable
private fun CoverRequestEffect(
    url: String,
    origin: String,
    overrideSize: IntSize?,
    waitForSize: Boolean,
    onLoaded: (Drawable) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(url, origin, overrideSize) {
        if (waitForSize && (overrideSize == null || overrideSize.width <= 0 || overrideSize.height <= 0)) {
            return@LaunchedEffect
        }
        val loaded = suspendCancellableCoroutine { continuation ->
            val target = object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (continuation.isActive) continuation.resume(resource)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // 释放由 invokeOnCancellation 的 Glide.clear 承担
                }
            }
            val builder = ImageLoader.load(context, url)
                .set(OkHttpModelLoader.sourceOriginOption, origin)
            val sized = if (overrideSize != null && overrideSize.width > 0 && overrideSize.height > 0) {
                builder.override(overrideSize.width, overrideSize.height)
            } else {
                builder
            }
            sized.into(target)
            continuation.invokeOnCancellation { Glide.with(context).clear(target) }
        }
        if (loaded != null) onLoaded(loaded)
    }
}

/**
 * Drawable → Painter 桥。
 *
 * 简化说明: 不引入 glide-compose 1.0.0-beta08（设计册 §2.4 GlideImage 伪代码两处失实：
 * ① beta08 GlideImage 无 onResourceReady 参数；② 其传递依赖 glide 5.0.5 与项目 glide 4.16.0
 * 版本对抗，ktx beta08 依赖 glide5 特有字段访问，兼容性无法静态证实）。
 * 已知上限: Animatable 动图不自动 start/stop（RSS 列表封面动图场景罕见）。
 * 升级路径: glide-compose 与项目 glide 主版本对齐后，可整体替换为 GlideImage。
 */
private class DrawableCoverPainter(private val drawable: Drawable) : Painter(), RememberObserver {

    override val intrinsicSize: Size
        get() {
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            return if (width > 0 && height > 0) Size(width.toFloat(), height.toFloat())
            else Size.Unspecified
        }

    override fun DrawScope.onDraw() {
        drawable.draw(drawContext.canvas.nativeCanvas)
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = Unit

    override fun onAbandoned() = Unit
}
