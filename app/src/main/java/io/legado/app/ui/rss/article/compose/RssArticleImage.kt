package io.legado.app.ui.rss.article.compose

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.data.appDb
import io.legado.app.help.CacheManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import kotlinx.coroutines.launch

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
 * **2026-09-28 用户报障修复（AD-05）**：原实现用 `AndroidView(FilletImageView)` 承载封面。RecyclerView
 * 路径有 ViewHolder 复用池（约 10 个 View 实例循环），而 LazyList 中 `AndroidView` **不保证底层 View 复用**
 * ⇒ 每行滑入都新建 `FilletImageView` 并在 `update` 中触发一次单行查库 + Glide 请求（View 创建发生在 UI 线程），
 * 快速下滑（fling）时每秒新建数十个 View —— 这是「非自由布局四样式都卡」的主要候选原因。
 * 现改为 **Compose 原生渲染**（对齐本仓存量范式 `ui/widget/compose/BookCoverImage.kt`，不新造轮子）：
 * `ImageLoader.loadBitmap` → `CustomTarget<Bitmap>` → `mutableStateOf<Bitmap>` → `Image` + `ContentScale.Crop`。
 *
 * 落地的三个硬约束（红队补充）：
 * ① 请求生命周期由 `DisposableEffect` 托管，组合离开时 `Glide.clear(target)` 取消（View 版依赖 View 回收，
 *   改 Compose 后必须显式取消，否则泄漏 target）；
 * ② 请求必须带 `override(w, h)`：改 Compose 后失去 `Glide.into(ImageView)` 的自动尺寸探测，
 *   不限制解码尺寸会按原图解码（样式 1/2 的整宽大封面会超采样）⇒ 用 `onSizeChanged` 取得实测尺寸后下发；
 * ③ 取数仍走 [RssArticleImageQuery]（单行查库），未加载/取数为空时保持留白（不显示错误占位，
 *   与原实现 `hideWhenBlank` 语义一致）。
 *
 * 视觉等价口径：
 * - 圆角裁剪由 `Modifier.clip(RoundedCornerShape(radiusDp.dp))` 承担（原 `FilletImageView.setCornerRadius` 同值）；
 * - `ContentScale.Crop` 与原 `scaleType = CENTER_CROP` 逐字一致；
 * - Glide 请求仍带 `OkHttpModelLoader.sourceOriginOption`（源站 Referer/UA 依赖，漏传会导致大量源取不到图）。
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
    val scope = rememberCoroutineScope()
    // 实测尺寸（首帧布局后得到）——用于给 Glide 下发 `override`，替代 View 版由 ImageView 自动提供的尺寸
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(origin, link) { mutableStateOf<Bitmap?>(null) }

    // 尺寸未测量出来前不发起请求（`IntSize.Zero` ⇒ 空 DisposableEffect），测量后 key 变化自动重启。
    // `bitmap != null` 守卫：瀑布流在比例回填（`onRatioResolved`）后会改变行高 ⇒ `boxSize` 变化会让本
    // effect 重启；此时已有图，必须跳过以免重复解码（否则每张卡在比例回填后都会多解一次码）。
    DisposableEffect(origin, link, boxSize, persistRatio) {
        val width = boxSize.width
        val height = boxSize.height
        if (origin.isNullOrBlank() || link.isNullOrBlank() || width <= 0 || height <= 0 || bitmap != null) {
            onDispose { }
        } else {
            var active = true
            var target: CustomTarget<Bitmap>? = null
            scope.launch {
                val image = currentQuery(origin, link)
                if (!active || image.isNullOrBlank()) return@launch
                // 比例是布局输入 ⇒ 先用已知缓存回填（避免瀑布流「先估算 → 解码后重排」的整屏跳动）
                if (persistRatio) currentRatio?.invoke(RssImageAspectRatioStore.get(image))
                val requestTarget = object : CustomTarget<Bitmap>(width, height) {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        if (!active) return
                        if (persistRatio) {
                            // 是否读 Bitmap 尺寸而不读 Drawable.intrinsic*：后者对部分来源（实测 `data:` 数据图）
                            // 会给出 ≤0 的无效值 ⇒ 瀑布流高度永远不正确。读 Bitmap.width/height 确定可靠。
                            val w = resource.width
                            val h = resource.height
                            if (w > 0 && h > 0) {
                                val ratio = h.toFloat() / w.toFloat()
                                RssImageAspectRatioStore.put(image, ratio)
                                currentRatio?.invoke(ratio)
                            }
                        }
                        bitmap = resource
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        if (active) bitmap = null
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (active) bitmap = null
                    }
                }
                target = requestTarget
                ImageLoader.loadBitmap(context, image)
                    .apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                    .into(requestTarget)
            }
            onDispose {
                active = false
                target?.let { runCatching { Glide.with(context.applicationContext).clear(it) } }
            }
        }
    }

    Box(modifier = modifier.onSizeChanged { boxSize = it }) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(radiusDp.dp)),
                contentScale = ContentScale.Crop
            )
        }
    }
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