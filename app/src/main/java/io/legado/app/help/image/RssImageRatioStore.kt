package io.legado.app.help.image

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.collection.LruCache
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.CacheManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.ui.rss.article.free.FreeGridSizeCalculator
import io.legado.app.utils.isDataUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import kotlin.math.roundToInt

/**
 * 「自由」布局的图片尺寸供给层（`文章 → 宽高比 w/h`）。
 *
 * 设计依据：`docs/specs/rss-free-layout/design.md`（算法二「尺寸供给与回填」）。
 *
 * ## 为什么需要这一层
 * 「自由」布局必须在**布局之前**知道一段连续 item 的宽高比，才能算出每行的高度与宽度。
 * 但本项目的 RSS 列表有两个硬约束：
 * 1. 列表主查询 `flowByOriginSort` 出于 CursorWindow 2MB 限制**不 select image 列**，
 *    image URL 必须按需异步单行查库（`RssArticleDao.getImage`）；
 * 2. 宽高比必须解码图片后才可知。
 * 因此布局路径无法自行取尺寸，必须由本层在后台批量预热。
 *
 * ## 三级缓存
 * | 层 | 键 | 说明 |
 * |---|---|---|
 * | L1 内存 | `rss_ar_v1_{origin}@{link}` | 布局路径唯一数据源，O(1) 纯内存读 |
 * | L2 磁盘 | 同上 | `CacheManager` KV，TTL 20 天，跨会话复用 |
 * | L3 解码 | — | Glide 落盘（或 base64 解码）→ `inJustDecodeBounds` 只读文件头 |
 *
 * 缓存键用 `origin@link` 而非 imageUrl，是因为 imageUrl 需异步查库才能拿到，
 * 无法用于同步布局路径；而 `origin` / `link` 在列表数据流中恒可得。
 *
 * ## 契约铁律
 * [peek] / [peekAll] **只允许**读内存 LruCache 与源级样本表。
 * **严禁**在其中调用 `CacheManager.getFloat()`（其内部是 `runBlocking(IO) { cacheDao.get }`，
 * 主线程调用会直接卡顿甚至 ANR），更严禁任何图片解码。
 * 磁盘访问与解码只在 [prefetch] 内、由调用方保证运行在 IO 线程上。
 */
object RssImageRatioStore {

    /** 磁盘键前缀。`v1` 用于后续缓存结构升级时整体失效 */
    const val KEY_PREFIX = "rss_ar_v1_"

    /** 瀑布流（RssArticlesAdapter3）的历史缓存前缀，其值是 **height/width**（倒数关系） */
    private const val LEGACY_KEY_PREFIX = "img_ar_"

    /** 磁盘缓存有效期 20 天（秒），与 RssArticlesAdapter3 口径一致 */
    private const val SAVE_TIME_SECONDS = 60 * 60 * 24 * 20

    /** 单批预取的最大并发数；过高会挤占订阅源列表本身的图片加载带宽 */
    private const val PREFETCH_CONCURRENCY = 4

    /** 每源保留的样本数上限，用于估算兜底（中位数）。FIFO 淘汰 */
    private const val SAMPLE_CAPACITY = 32

    /** 内存缓存容量（条）。按每条约 40 字节估算，1024 条约 40KB */
    private const val MEMORY_CACHE_SIZE = 1024

    /** L1 内存缓存。androidx 的 LruCache 内部已做同步，可跨线程访问 */
    private val memory = LruCache<String, Float>(MEMORY_CACHE_SIZE)

    /** 源 → 已解析比例样本（用于 ratio 未知时估算）。ArrayDeque 非线程安全，访问需持锁 */
    private val sourceSamples = HashMap<String, ArrayDeque<Float>>()

    /**
     * 正在解析中的缓存键集合。
     *
     * 用于滚动过程中反复触发预取时**精确去重**：同一张图不会被重复查库/下载。
     * 相比「单调递增的已预取上界」方案，本方案在批次被取消时不会造成永久跳过的区间。
     */
    private val inFlightKeys: MutableSet<String> =
        Collections.newSetFromMap(HashMap<String, Boolean>())

    /** 组装缓存键 */
    private fun keyOf(origin: String, link: String) = "$KEY_PREFIX$origin@$link"

    /**
     * 布局路径唯一入口：取单张图的宽高比。
     *
     * 未命中时返回**源级中位数**作估算（无样本则 [FreeGridSizeCalculator.DEFAULT_RATIO]），
     * 保证布局永远有值可用，不会因尺寸缺失而塌陷或留空洞。
     *
     * 纯内存操作，O(1)，零 I/O —— 可在 `onLayoutChildren` 等主线程路径安全调用。
     */
    fun peek(origin: String, link: String): Float {
        memory[keyOf(origin, link)]?.let { return it }
        return medianOf(origin)
    }

    /** 批量取比例，返回数组与 [links] 一一对应。供 LayoutManager 一次性喂给预计算器 */
    fun peekAll(origin: String, links: List<String>): FloatArray {
        val fallback = medianOf(origin)
        return FloatArray(links.size) { index ->
            memory[keyOf(origin, links[index])] ?: fallback
        }
    }

    /** 是否已解析出真实比例（区别于 [peek] 的估算值） */
    fun hasRatio(origin: String, link: String): Boolean = memory[keyOf(origin, link)] != null

    /**
     * 批量预取比例。**必须在 IO 线程调用**。
     *
     * @param from 起始下标（含）
     * @param count 本次预取条数
     * @return 本次实际新解析出的条目数；0 表示全部命中缓存（调用方据此跳过重排）
     */
    suspend fun prefetch(
        context: Context,
        origin: String,
        articles: List<RssArticle>,
        from: Int,
        count: Int
    ): Int {
        if (count <= 0 || articles.isEmpty()) return 0
        val start = from.coerceAtLeast(0)
        val end = (start + count).coerceAtMost(articles.size)
        if (start >= end) return 0

        val semaphore = Semaphore(PREFETCH_CONCURRENCY)
        val results = coroutineScope {
            (start until end).map { index ->
                async {
                    semaphore.withPermit { prefetchOne(context, origin, articles[index]) }
                }
            }.awaitAll()
        }
        return results.count { it }
    }

    /**
     * 预取单条。返回是否新解析出比例。
     *
     * 失败路径只记日志、**不写缓存** —— 保证下次进入仍可重试；
     * 否则一次偶发失败会被缓存 20 天，该图永久退化为估算值。
     */
    private suspend fun prefetchOne(context: Context, origin: String, article: RssArticle): Boolean {
        val link = article.link
        if (link.isBlank()) return false
        val key = keyOf(origin, link)
        if (memory[key] != null) return false
        // 精确去重：同一张图已在本进程的其他批次中解析时直接跳过
        if (!inFlightKeys.add(key)) return false
        try {
            // L2 磁盘命中：跨会话复用，无需重新下载解码
            diskRatio(key)?.let {
                store(origin, link, it)
                return true
            }
            // L3：查 image URL → 解析比例
            val imageUrl = runCatching { appDb.rssArticleDao.getImage(origin, link) }.getOrNull()
            if (imageUrl.isNullOrBlank()) return false
            val ratio = resolveRatio(context, origin, imageUrl) ?: return false
            store(origin, link, ratio)
            // 成功后立刻写磁盘，供下次会话直接命中
            runCatching {
                CacheManager.put(key, ratio, SAVE_TIME_SECONDS)
            }.onFailure {
                AppLog.put("自由布局 ratio 写磁盘失败：origin=${origin.take(2)}***", it)
            }
            return true
        } catch (e: Exception) {
            logFailure(origin, e)
            return false
        } finally {
            inFlightKeys.remove(key)
        }
    }

    /**
     * 解析单张图的宽高比，三条路径按成本从低到高依次尝试：
     * 1. 复用瀑布流历史缓存（键为 imageUrl，值为 h/w，需取倒数）
     * 2. base64 内联图（`data:` 开头）直接解码字节
     * 3. 网络图：Glide 落盘（带防盗链请求头）后只读文件头
     *
     * @return 已钳制到 `[MIN_RATIO, MAX_RATIO]` 的 w/h；无法解析时返回 null
     */
    private suspend fun resolveRatio(context: Context, origin: String, imageUrl: String): Float? {
        // 1) 历史缓存桥接：RssArticlesAdapter3（瀑布流）以 imageUrl 为键存 height/width
        val legacy = runCatching { CacheManager.getFloat(LEGACY_KEY_PREFIX + imageUrl) }.getOrNull()
        if (legacy != null && legacy > 0f) {
            val ratio = clampRatio(1f / legacy)
            if (ratio != null) return ratio
        }
        // 2) base64 内联图：直接解码字节，不落盘
        if (imageUrl.isDataUrl()) {
            return runCatching {
                val bytes = decodeDataUrl(imageUrl) ?: return@runCatching null
                ratioOfBytes(bytes)
            }.getOrElse {
                logFailure(origin, it)
                null
            }
        }
        // 3) 网络图：Glide 落盘（复用其磁盘缓存）后只读文件头
        return runCatching {
            val options = RequestOptions()
                // 防盗链：与列表展示使用完全相同的请求头，否则部分源会 403 导致永久落回估算值
                .set(OkHttpModelLoader.sourceOriginOption, origin)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
            val file = ImageLoader.loadFile(context, imageUrl)
                .apply(options)
                // 超时防挂死：单图探测最长 10s（大图慢源不拖死 IO 线程池，超时按失败回落估算值）
                .submit()
                .get(10, java.util.concurrent.TimeUnit.SECONDS)
            ratioOfFile(file?.absolutePath)
        }.getOrElse {
            logFailure(origin, it)
            null
        }
    }

    /**
     * 只读图片文件头取宽高，**不做像素解码**（`inJustDecodeBounds`）。
     *
     * @return 已钳制的 w/h；文件不可读或非图片格式时返回 null
     */
    private fun ratioOfFile(path: String?): Float? {
        if (path.isNullOrBlank()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return ratioOfSize(options.outWidth, options.outHeight)
    }

    /** 同上，但数据来源是内存字节数组（base64 内联图） */
    private fun ratioOfBytes(bytes: ByteArray): Float? {
        if (bytes.isEmpty()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ratioOfSize(options.outWidth, options.outHeight)
    }

    /** `outWidth/outHeight` → 钳制后的 w/h；解码失败（宽高为 0）时返回 null */
    private fun ratioOfSize(width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return null
        return clampRatio(width.toFloat() / height.toFloat())
    }

    /** 比例钳制（AD-09 策略 X）。非法值返回 null，越界值收敛到区间端点 */
    private fun clampRatio(ratio: Float): Float? {
        if (ratio.isNaN() || ratio <= 0f) return null
        return ratio.coerceIn(
            FreeGridSizeCalculator.MIN_RATIO,
            FreeGridSizeCalculator.MAX_RATIO
        )
    }

    /** 解析 `data:image/xxx;base64,<payload>`；非 base64 载荷（如 urlencoded）返回 null */
    private fun decodeDataUrl(dataUrl: String): ByteArray? {
        val base64Index = dataUrl.indexOf(";base64,", ignoreCase = true)
        if (base64Index < 0) return null
        val payload = dataUrl.substring(base64Index + ";base64,".length)
        if (payload.isBlank()) return null
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    /** 写入内存缓存 + 源级样本 */
    private fun store(origin: String, link: String, ratio: Float) {
        memory.put(keyOf(origin, link), ratio)
        synchronized(sourceSamples) {
            val samples = sourceSamples.getOrPut(origin) { ArrayDeque(SAMPLE_CAPACITY) }
            if (samples.size >= SAMPLE_CAPACITY) samples.removeFirst()
            samples.addLast(ratio)
        }
    }

    /** 读磁盘缓存。仅允许在 IO 线程调用（`CacheManager.getFloat` 内部是 runBlocking 查库） */
    private fun diskRatio(key: String): Float? {
        val ratio = runCatching { CacheManager.getFloat(key) }.getOrNull() ?: return null
        return clampRatio(ratio)
    }

    /**
     * 源级估算比例：取已解析样本的中位数。
     * 中位数比均值更抗极端值（全景图/长条图）干扰，适合作为「未知图片」的占位比例。
     */
    private fun medianOf(origin: String): Float {
        val sorted = synchronized(sourceSamples) {
            sourceSamples[origin]?.toFloatArray()?.sortedArray()
        } ?: return FreeGridSizeCalculator.DEFAULT_RATIO
        if (sorted.isEmpty()) return FreeGridSizeCalculator.DEFAULT_RATIO
        return sorted[sorted.size / 2]
    }

    /**
     * 失败日志。**严禁输出域名 / 完整 URL**（输出安全规范），只保留 origin 前 2 字符 + 异常类名。
     */
    private fun logFailure(origin: String, error: Throwable) {
        AppLog.put(
            "自由布局 ratio 预取失败：origin=${origin.take(2)}***, " +
                "err=${error.javaClass.simpleName}"
        )
    }

    /** 切换订阅源时清理该源的样本与在途标记（内存缓存保留，键本身含 origin 不会串源） */
    fun resetSource(origin: String) {
        synchronized(sourceSamples) { sourceSamples.remove(origin) }
    }

    /** 供调试/单测观察：当前内存缓存条数 */
    fun memorySize(): Int = memory.size()

    /** 供调试/单测观察：四舍五入到两位小数的比例，null 表示未解析 */
    internal fun peekOrNull(origin: String, link: String): Float? =
        memory[keyOf(origin, link)]?.let { (it * 100f).roundToInt() / 100f }
}
