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