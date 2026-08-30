package io.legado.app.help.glide

import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.util.ContentLengthInputStream
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.MemoryPressure
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.StreamResetRetryInterceptor
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.okHttpClientManga
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.ReadManga
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream


class OkHttpStreamFetcher(
    private val url: GlideUrl,
    private val options: Options,
) :
    DataFetcher<InputStream>, okhttp3.Callback {
    private var stream: InputStream? = null
    private var responseBody: ResponseBody? = null
    private var callback: DataFetcher.DataCallback<in InputStream>? = null
    private var source: BaseSource? = null
    private val manga = options.get(OkHttpModelLoader.mangaOption) == true
    private val coroutineContext = SupervisorJob()
    private val coroutineScope = CoroutineScope(coroutineContext)
    private lateinit var analyzedUrl: GlideUrl

    @Volatile
    private var call: Call? = null

    companion object {
        private const val TAG = "ImgDecrypt"
        // F-P1-C4 修复无界 HashSet 内存泄漏 | 已知上限：200 条失败 URL | 升级路径：无
        private val failUrl = android.util.LruCache<String, Boolean>(200)

        /** H3(sniff-regression-rss-image-crash): 小内存设备超过该大小的图片跳过解密透传（10MB） */
        internal const val SKIP_DECODE_SIZE_BYTES = 10L * 1024 * 1024

        /** AD-03: 有界缓冲读取结果（exceeded=true 表示超过 limit，调用方透传） */
        internal class BoundedRead(val bytes: ByteArray, val exceeded: Boolean)

        /**
         * AD-03(enhance-switch-governance-fix): 有界缓冲读取——增量读入至 limit+1 上限
         * 内存峰值 ≤ limit + 单块缓冲，与既有 decode readBytes 路径等价量级，堵住 chunked
         * 无长度响应绕过小内存 OOM 守卫的缺口；恰好等于 limit 时视为未超限（与既有
         * contentLength > SKIP 语义一致）
         */
        internal fun readBounded(body: ResponseBody, limit: Long): BoundedRead {
            val input = body.byteStream()
            val out = ByteArrayOutputStream(if (limit < Int.MAX_VALUE) (limit / 4).coerceAtMost(4L * 1024 * 1024).toInt() else Int.MAX_VALUE)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n == -1) return BoundedRead(out.toByteArray(), false)
                out.write(buf, 0, n)
                total += n
                if (total > limit) return BoundedRead(out.toByteArray(), true)
            }
        }
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        // I-P0-2: 降级链主动重试（bypassFailCacheOption=true）时绕过失败缓存短路，
        // 否则同 URL 重试永不发请求（86 张 403 仅 1 张真实降级的根因）
        val bypassFailCache = options.get(OkHttpModelLoader.bypassFailCacheOption) == true
        if (!bypassFailCache && failUrl.get(url.toStringUrl()) != null) {
            callback.onLoadFailed(NoStackTraceException("跳过加载失败的图片"))
            return
        }
        val loadOnlyWifi = options.get(OkHttpModelLoader.loadOnlyWifiOption) ?: false
        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            callback.onLoadFailed(NoStackTraceException("只在wifi加载图片"))
            return
        }

        val sourceUrl = options.get(OkHttpModelLoader.sourceOriginOption)
        if (sourceUrl != null) {
            source = SourceHelp.getSource(sourceUrl)
        }
        // 使用 Log.e 直接输出到 logcat（不依赖 AppLog），确保诊断信息必定可见
        Log.e(TAG, "loadData: source=${source?.getKey()}, manga=$manga")

        analyzedUrl = AnalyzeUrl(
            url.toString(),
            source = source,
            coroutineContext = coroutineContext
        ).getGlideUrl()

        val requestBuilder = Request.Builder().url(analyzedUrl.toStringUrl())
        requestBuilder.addHeaders(analyzedUrl.headers)
        // 修复（image-gallery）：图片防盗链失败，如果订阅源 header 未配置 Referer，用 refererOption 兜底
        // 网页模式 WebView 会自动带文章页 URL 作为 Referer，图片模式需要手动注入
        val referer = options.get(OkHttpModelLoader.refererOption)
        if (!referer.isNullOrBlank() && analyzedUrl.headers["Referer"] == null
            && analyzedUrl.headers["referer"] == null
        ) {
            requestBuilder.addHeader("Referer", referer)
            Log.e(TAG, "inject Referer from refererOption: refererLen=${referer.length}")
        }
        val request: Request = requestBuilder.build()
        this.callback = callback
        call = if (manga) {
            okHttpClientManga.newCall(request)
        } else {
            okHttpClient.newCall(request)
        }
        call?.enqueue(this)
    }

    override fun cleanup() {
        kotlin.runCatching {
            stream?.close()
        }
        responseBody?.close()
        coroutineContext.cancel()
        callback = null
    }

    override fun cancel() {
        call?.cancel()
        coroutineContext.cancel()
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.REMOTE
    }

    override fun onFailure(call: Call, e: IOException) {
        Log.e(TAG, "onFailure: url=${url.toStringUrl().take(80)}, error=${e.message}")
        // sniff-result-pipeline-fix FR-3: StreamResetException 不写入 failUrl
        // 根因：StreamReset 是 HTTP/2 流重置，连接池连接未淘汰，下次复用仍失败
        // 方案：StreamResetException 不写入 failUrl，允许后续请求重试（配合 StreamResetRetryInterceptor）
        // 注：原 onFailure 未写入 failUrl，只有 onResponse 非 2xx 才写入，此处保持原逻辑
        if (StreamResetRetryInterceptor.isStreamResetException(e)) {
            AppLog.put("Glide onFailure StreamReset (HTTP/2 流重置, 已由 StreamResetRetryInterceptor 重试)")
        }
        callback?.onLoadFailed(e)
    }

    override fun onResponse(call: Call, response: Response) {
        responseBody = response.body
        if (!response.isSuccessful) {
            if (!manga) {
                failUrl.put(url.toStringUrl(), true)
            }
            callback?.onLoadFailed(HttpException(response.message, response.code))
            return
        }
        // I-P0-2: 降级重试成功后清除失败缓存旧记录，后续普通 bind 不再被短路
        if (options.get(OkHttpModelLoader.bypassFailCacheOption) == true) {
            failUrl.remove(url.toStringUrl())
        }
        val isCover = !manga
        val needDecode = !ImageUtils.skipDecode(source, isCover)

        if (!needDecode) {
            onStreamReady(responseBody!!.byteStream())
            return
        }
        Coroutine.async(coroutineScope, executeContext = IO) {
            val decodeResult = runScriptWithContext(coroutineContext) {
                if (manga) {
                    ImageUtils.decode(
                        url.toString(),
                        responseBody!!.bytes(),
                        isCover = false,
                        source,
                        ReadManga.book
                    )?.inputStream()
                } else {
                    // H3(sniff-regression-rss-image-crash): 小内存设备（heap≤320MB）超大图跳过解密
                    // 根因：decode(InputStream) 内 readBytes() 全量读入，解密期间原始+结果双份 byte[]
                    // 存活（峰值 2×图体积），256MB heap 上多张并发极易触顶 OOM；
                    // 超大图（>10MB）加密概率极低（已知格式本来就会被文件头检测跳过），直接透传
                    // AD-03(enhance-switch-governance-fix)：chunked/无长度响应 contentLength=-1 绕过守卫，
                    // 改为有界缓冲探测——增量读至 SKIP+1 上限，超限透传，未超限对缓冲字节走 decode
                    val contentLength = responseBody?.contentLength() ?: -1
                    when {
                        MemoryPressure.isSmallHeap && contentLength > SKIP_DECODE_SIZE_BYTES -> {
                            Log.e(TAG, "small-heap skip decode: len=$contentLength url=${analyzedUrl.toStringUrl().take(60)}")
                            responseBody!!.byteStream()
                        }
                        MemoryPressure.isSmallHeap && contentLength < 0 -> {
                            val bounded = readBounded(responseBody!!, SKIP_DECODE_SIZE_BYTES)
                            if (bounded.exceeded) {
                                Log.e(TAG, "small-heap skip decode: len=unknown(>limit) url=${analyzedUrl.toStringUrl().take(60)}")
                                SequenceInputStream(
                                    ByteArrayInputStream(bounded.bytes),
                                    responseBody!!.byteStream()
                                )
                            } else {
                                ImageUtils.decode(
                                    analyzedUrl.toStringUrl(),
                                    ByteArrayInputStream(bounded.bytes),
                                    isCover = true, source
                                )
                            }
                        }
                        else -> {
                            ImageUtils.decode(
                                analyzedUrl.toStringUrl(), responseBody!!.byteStream(),
                                isCover = true, source
                            )
                        }
                    }
                }
            }
            onStreamReady(decodeResult)
        }
    }

    private fun onStreamReady(inputStream: InputStream?) {
        if (inputStream == null) {
            if (!manga) {
                failUrl.put(url.toStringUrl(), true)
            }
            callback?.onLoadFailed(NoStackTraceException("封面二次解密失败"))
        } else {
            val contentLength: Long =
                if (inputStream is ByteArrayInputStream) inputStream.available().toLong()
                else responseBody!!.contentLength()
            stream = ContentLengthInputStream.obtain(inputStream, contentLength)
            callback?.onDataReady(stream)
        }
    }

}
