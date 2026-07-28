package io.legado.app.help.glide

import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import java.io.InputStream

object OkHttpModelLoader : ModelLoader<GlideUrl?, InputStream?> {

    val loadOnlyWifiOption = Option.memory("loadOnlyWifi", false)
    val sourceOriginOption = Option.memory<String>("sourceOrigin")
    val mangaOption = Option.memory<Boolean>("manga",false)
    // 修复（image-gallery）：图片防盗链失败，网页模式 WebView 自动带文章页 URL 作为 Referer
    // 图片模式 Glide 需手动注入 Referer，用文章页 URL（article.origin）作为 Referer
    val refererOption = Option.memory<String>("referer")

    /**
     * I-P0-2: 绕过 fetcher 失败 URL 缓存（failUrl）短路
     *
     * 根因：OkHttpStreamFetcher 对非 2xx 响应将 URL 写入 failUrl（LRU 200），
     * loadData 入口命中即直接 onLoadFailed 不发请求——降级链同 URL 重试全部被
     * 短路（86 张 403 仅 1 张走入真实降级的机制性根因）。
     *
     * 使用：降级链主动重试 / WebView 预热后重载时置 true；普通 bind 不设置
     * （首次失败缓存仍生效，避免重复无效请求浪费流量）。
     */
    val bypassFailCacheOption = Option.memory<Boolean>("bypassFailCache", false)

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream?> {
        return ModelLoader.LoadData(model, OkHttpStreamFetcher(model, options))
    }

    override fun handles(model: GlideUrl): Boolean {
        return true
    }

}