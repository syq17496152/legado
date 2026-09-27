package io.legado.app.ui.rss.article.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.data.appDb
import io.legado.app.help.CacheManager
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.widget.image.FilletImageView
import io.legado.app.utils.dpToPx

/** 封面图取数通道：`(origin, link) -> image 字段`。文章列表与收藏列表各走自己的 DAO。 */
typealias RssArticleImageQuery = suspend (origin: String, link: String) -> String?

/** 默认通道：订阅文章表按需单行取 `image`。 */
val DefaultRssArticleImageQuery: RssArticleImageQuery = { origin, link ->
    appDb.rssArticleDao.getImage(origin, link)
}

/**
 * 订阅文章封面（Compose 侧**单源**，CF 6.2 RSS 文章五样式族）。
 *
 * 为什么不是「直接读列表项里的 image 字段」：`RssArticleDao.flowByOriginSort` 刻意不 select `image`
 * （部分源把封面存成 base64 数据图，实测单行最大 395KB，多行一次性 select 会挤满 CursorWindow 2MB
 * 窗口导致读取失败）⇒ 必须**逐项单行查库**（单行远小于窗口，安全且大图完整）。
 * 本组件把原三处重复实现收敛为一处：`BaseRssArticlesAdapter.loadArticleImage`（样式 0/1/2/4）、
 * `RssArticlesAdapter3.convert` 内联分支（样式 3 的比例回填）、`RssFavoritesAdapter.convert`（收藏页）。
 *
 * 视觉/行为等价口径：
 * - 圆角裁剪沿用项目自定义 View `FilletImageView.setCornerRadius`（与原 `app:radius` 同源）；
 * - `scaleType = CENTER_CROP` 与原 XML 逐字一致；
 * - **未加载完成/取数为空时该位留白**——原实现非网格样式的 `image_view` 初始 `gone`、网格样式无 `src`
 *   且 `placeholder` 取透明占位图，两者观感同为「空白」（故无需在 Compose 侧重造占位图）；
 * - Glide 请求带 `OkHttpModelLoader.sourceOriginOption`（源站 Referer/UA 依赖，漏传会导致大量源取不到图）。
 *
 * 与 View 侧的差异（有意，非等价项）：原实现把「比例 → `layoutParams.height`」写在 Adapter 里并配
 * `adjustViewBounds`；Compose 侧改由**行内 `Modifier.aspectRatio`** 表达（见 [onRatioResolved]），
 * 结果一致（图片按真实宽高比占位），但不再改写 View 的 layoutParams。
 *
 * @param onRatioResolved 首帧可用比例回调（`h/w`；`0f` = 未知）。瀑布流（articleStyle=3）据此决定行高。
 * @param persistRatio    是否把首帧真实比例写入 20 天持久缓存（瀑布流专用，原 `RssArticlesAdapter3` 口径）。
 */
@Composable
fun RssArticleImage(
    origin: String?,
    link: String?,
    modifier: Modifier = Modifier,
    radiusDp: Int = 12,
    queryImage: RssArticleImageQuery = DefaultRssArticleImageQuery,
    onRatioResolved: ((Float) -> Unit)? = null,
    persistRatio: Boolean = false
) {
    val context = LocalContext.current
    val currentQuery by rememberUpdatedState(queryImage)
    val currentRatio by rememberUpdatedState(onRatioResolved)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FilletImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setCornerRadius(radiusDp.dpToPx())
            }
        },
        update = { imageView ->
            // update 每次重组都会执行 ⇒ 用 tag 记住「本实例已触发的取图键」，避免重复查库/重复下载；
            // 同键即当前图，直接早退（原实现用 holder.itemView.tag 防复用错位，语义相同）
            val key = "$origin|$link"
            if (imageView.tag == key) return@AndroidView
            imageView.tag = key
            imageView.setImageDrawable(null)
            loadRssArticleImage(
                context, imageView, origin, link, key,
                currentQuery, persistRatio, currentRatio
            )
        }
    )
}

private fun loadRssArticleImage(
    context: Context,
    imageView: ImageView,
    origin: String?,
    link: String?,
    key: String,
    query: RssArticleImageQuery,
    persistRatio: Boolean,
    onRatioResolved: ((Float) -> Unit)?
) {
    if (origin.isNullOrBlank() || link.isNullOrBlank()) return
    Coroutine.async {
        query(origin, link)
    }.onSuccess { image ->
        if (imageView.tag != key || image.isNullOrBlank()) return@onSuccess
        val options = RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin)
        if (persistRatio) {
            // 瀑布流：比例是布局输入 ⇒ 必须拿到**确定的像素尺寸**。
            // 为什么读 `Bitmap` 而不读回调里的 `Drawable.intrinsic*`：后者对部分来源（实测 `data:` 数据图）
            // 会给出 ≤0 的无效值，而 `intrinsic ≤ 0` 时既学不到比例、也会让卡片停在「比例未知」的兜底态
            // ⇒ 瀑布流高度永远不正确。改走 `CustomTarget<Bitmap>` 直接读 `Bitmap.width/height` 是确定可靠的。
            loadCachedRatioThenBitmap(context, imageView, image, key, options, onRatioResolved)
            return@onSuccess
        }
        ImageLoader.load(context, image).apply(options).into(imageView)
    }.onError {
        // 取数失败 ⇒ 保持留白（与原 `hideWhenBlank` 语义一致：该位不显示图，不做错误占位）
        if (imageView.tag == key) imageView.setImageDrawable(null)
    }
}

/** 先回填已知比例（避免「先估算后跳动」），再用 bitmap 真实尺寸校正并落盘缓存。 */
private fun loadCachedRatioThenBitmap(
    context: Context,
    imageView: ImageView,
    image: String,
    key: String,
    options: RequestOptions,
    onRatioResolved: ((Float) -> Unit)?
) {
    onRatioResolved?.invoke(RssImageAspectRatioStore.get(image))
    ImageLoader.loadBitmap(context, image)
        .apply(options)
        .into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (imageView.tag != key) return
                val width = resource.width
                val height = resource.height
                if (width > 0 && height > 0) {
                    val ratio = height.toFloat() / width.toFloat()
                    RssImageAspectRatioStore.put(image, ratio)
                    onRatioResolved?.invoke(ratio)
                }
                imageView.setImageBitmap(resource)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                if (imageView.tag == key) imageView.setImageDrawable(null)
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (imageView.tag == key) imageView.setImageDrawable(null)
            }
        })
}

/**
 * 瀑布流图片宽高比缓存（原 `RssArticlesAdapter3.companion` 逐字迁移）。
 *
 * 内存 LRU 399 条 + 磁盘 20 天（`CacheManager`）：比例是「布局定型」的输入，首屏命中可避免
 * 「先估算 → 解码后重排」的整屏跳动。
 */
object RssImageAspectRatioStore {
    private val imageAspectRatio = LruCache<String, Float>(399)
    private const val KEY_NAME = "img_ar_"
    private const val SAVE_TIME = 60 * 60 * 24 * 20 // 20天

    /** 已知比例；`0f` = 未知（调用方据此退化为「不占高」） */
    fun get(url: String): Float {
        imageAspectRatio[url]?.let { return it }
        CacheManager.getFloat(KEY_NAME + url)?.let {
            imageAspectRatio.put(url, it)
            return it
        }
        return 0f
    }

    fun put(url: String, aspectRatio: Float) {
        if (aspectRatio <= 0f) return
        imageAspectRatio.put(url, aspectRatio)
        CacheManager.put(KEY_NAME + url, aspectRatio, SAVE_TIME)
    }
}