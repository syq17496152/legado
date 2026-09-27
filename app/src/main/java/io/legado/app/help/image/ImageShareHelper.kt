package io.legado.app.help.image

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.utils.toastOnUi
import java.io.File

/**
 * W5 6.4 / REQ-23：图片**真分享**（FileProvider + `ACTION_SEND`）。
 *
 * 为什么必须改（实证根因）：原实现是「把图片 URL 复制到剪贴板」（`sendToClip`），
 * 名为分享实为复制链接 —— 收件方拿不到图片本体，且链接多为带防盗链的 CDN 地址。
 *
 * 实现口径（与既有保存链路同源，不新增下载通道）：
 * 1. 用 `Glide.asFile()` 取**磁盘缓存文件**（沿用 `OkHttpModelLoader` 的 Referer/Cookie 注入，
 *    见 `OkHttpModelLoader.sourceOriginOption`）—— 与 `ImageCanvasViewModel.saveImage` 同一取图方式；
 * 2. 复制到 `cacheDir/share_image/` 并**保留扩展名**（Glide 缓存文件名是哈希、无扩展名，
 *    直接分享会因 MIME 推断失败而被接收方拒收；且 `cache-path` 已在 `res/xml/file_paths.xml` 声明）；
 * 3. `FileProvider.getUriForFile` + `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` 调起系统分享面板。
 *
 * 复用点：W7 8.1（漫画长按存图/分享）必须复用本入口，禁止另起一套分享实现。
 */
object ImageShareHelper {

    /** 分享文件落点目录（`cacheDir` 下；已被 `file_paths.xml` 的 `cache-path` 覆盖）。 */
    private const val SHARE_DIR = "share_image"

    private const val MIME_IMAGE = "image/*"

    /**
     * 异步分享一张图片（IO 取缓存文件 → 主线程调起分享面板）。
     *
     * @param imageUrl 图片地址（网络/本地路径均可，走 `ImageLoader.loadFile` 分流）
     * @param sourceOrigin 订阅源 URL：用于注入 Referer/Cookie（解决 CDN 防盗链）
     * @param displayName 分享文件名（语义化命名由 [ImageFileNameBuilder] 生成）
     */
    fun shareImage(
        activity: Activity,
        imageUrl: String,
        sourceOrigin: String?,
        displayName: String,
    ) {
        Coroutine.async<Uri> {
            // 同步取缓存文件（本协程已在 IO 线程）
            val cachedFile = ImageLoader.loadFile(activity, imageUrl).apply {
                sourceOrigin?.let { origin ->
                    apply(RequestOptions().set(OkHttpModelLoader.sourceOriginOption, origin))
                }
            }.submit().get()
            val dir = File(activity.cacheDir, SHARE_DIR).apply { mkdirs() }
            val target = File(dir, displayName)
            cachedFile.copyTo(target, overwrite = true)
            FileProvider.getUriForFile(activity, AppConst.authority, target)
        }.onSuccess { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = MIME_IMAGE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 无匹配应用（部分精简系统）时 createChooser 仍可弹面板；取图/授权失败走 onError
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share)))
        }.onError { e ->
            AppLog.put("图片分享失败", e, true)
            activity.toastOnUi(
                activity.getString(R.string.image_share_failed, e.localizedMessage.orEmpty())
            )
        }
    }
}