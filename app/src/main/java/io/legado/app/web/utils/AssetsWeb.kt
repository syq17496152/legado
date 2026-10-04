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
        val response = NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            mimeOf(assetPath),
            inputStream
        )
        // 缓存策略（2026-10-04 真机卡顿修复）：此前**不带任何缓存头** ⇒ 每次打开控制台都重下全部资源
        //（首屏约 900KB gzip），真机体验极差。Vite 产物文件名带内容哈希 ⇒ `/assets/*` 可长缓存；
        // index.html 必须每次回源，否则升级后仍引用旧哈希文件。
        response.addHeader("Cache-Control", cacheControlOf(path))
        return response
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
         * 缓存策略（2026-10-04 真机卡顿修复）。
         *
         * 判定依据 = **路径是否位于 Vite 产物目录 `/assets/`**：该目录下文件名带内容哈希
         * （如 `vendor-Beqdr9ys.js`）⇒ 内容与文件名一一对应，可 `immutable` 长缓存。
         * 其余（`index.html`、`favicon.ico` 等）一律 `no-cache`：每次都回源校验，
         * 保证 App 升级后立即引用新哈希产物，不会拿到旧页面。
         */
        internal fun cacheControlOf(path: String): String =
            if (path.contains("/assets/")) {
                "public, max-age=31536000, immutable"
            } else {
                "no-cache"
            }

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
