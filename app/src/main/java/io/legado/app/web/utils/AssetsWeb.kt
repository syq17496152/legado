package io.legado.app.web.utils

import android.content.res.AssetManager
import android.text.TextUtils
import fi.iki.elonen.NanoHTTPD
import splitties.init.appCtx
import java.io.File
import java.io.IOException


class AssetsWeb(rootPath: String) {
    private val assetManager: AssetManager = appCtx.assets
    private var rootPath = "web"

    init {
        if (!TextUtils.isEmpty(rootPath)) {
            this.rootPath = rootPath
        }
    }

    @Throws(IOException::class)
    fun getResponse(path: String): NanoHTTPD.Response {
        // 路径穿越防护（web-mcp-productization 一期 · 3.5 / REQ-1-305）：
        // 不合法路径直接 400，**不拼路径、不打开文件**（防 `../` 读到 assets 外/非预期资源）。
        if (!isSafePath(path)) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "bad request path"
            )
        }
        val assetPath = (rootPath + path).replace("/+".toRegex(), File.separator)
        val inputStream = assetManager.open(assetPath)
        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            mimeOf(assetPath),
            inputStream
        )
    }

    companion object {

        /**
         * 路径安全（3.5 / REQ-1-305）：含 `..`、含 `\u0000`、或不以 `/` 开头 ⇒ 不安全。
         *
         * 说明：本服务只暴露 assets 内静态资源，`..` 无任何合法用途 ⇒ 一律拒绝（不做规范化后放行，
         * 避免"规范化实现细节"成为绕过面）。
         */
        internal fun isSafePath(path: String): Boolean =
            path.startsWith("/") && !path.contains("..") && !path.contains('\u0000')

        /**
         * MIME 判定（3.4 / REQ-1-304）。
         *
         * 行为变更（已登记 updateLog 口径）：`.jpg` 由原 `image/jpg`（非标准）改为标准值 `image/jpeg`。
         * 无扩展名时回落 `text/html`（且不得抛异常 —— 原实现对无点号路径会 `substring(-1)` 崩）。
         */
        internal fun mimeOf(path: String): String {
            val dot = path.lastIndexOf('.')
            val suffix = if (dot >= 0) path.substring(dot).lowercase() else ""
            return when (suffix) {
                ".html", ".htm" -> "text/html"
                ".js", ".mjs" -> "text/javascript"
                ".css" -> "text/css"
                ".ico" -> "image/x-icon"
                ".jpg", ".jpeg" -> "image/jpeg"
                ".png" -> "image/png"
                ".svg" -> "image/svg+xml"
                ".woff" -> "font/woff"
                ".woff2" -> "font/woff2"
                ".ttf" -> "font/ttf"
                ".json", ".map" -> "application/json"
                else -> "text/html"
            }
        }
    }
}
