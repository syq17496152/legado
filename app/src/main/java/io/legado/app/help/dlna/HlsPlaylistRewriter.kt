package io.legado.app.help.dlna

import java.net.URL

/**
 * add-dlna-cast：m3u8（HLS）清单重写（design AD-04）。
 *
 * 为什么必须重写：带鉴权的 m3u8 直投必 403（渲染端带不了 Referer/Cookie）；
 * 而只把**清单本身**过代理是没用的 —— 清单里每条分片 URI 仍指向原站，
 * 渲染端会绕过代理直接去拉，照样 403。因此清单里**每一个 URI 承载点**都要改写。
 *
 * 覆盖的承载点（红队第 4 轮把原设计的漏项补齐）：
 *  | 形态 | 说明 |
 *  |------|------|
 *  | 普通行 | 分片 / 变体地址（最常见） |
 *  | `#EXT-X-STREAM-INF` 的下一行 | master 清单的变体地址 |
 *  | `#EXT-X-STREAM-INF` **行内 `URI=`** | HLS 允许变体地址写在同行属性里（原设计漏项，真缺陷） |
 *  | `#EXT-X-KEY:URI=` | AES-128 密钥地址 |
 *  | `#EXT-X-SESSION-KEY:URI=` | |
 *  | `#EXT-X-MAP:URI=` | fMP4/CMAF 的初始化段 |
 *  | `#EXT-X-MEDIA:URI=` | 备用音轨/字幕 |
 *  | `#EXT-X-I-FRAME-STREAM-INF:URI=` | I 帧索引 |
 *  | `#EXT-X-PART:URI=` / `#EXT-X-PRELOAD-HINT:URI=` | 低延迟 HLS（LL-HLS） |
 *  | `#EXT-X-DATERANGE:X-ASSET-URI=` | 伴随广告资产 |
 *
 * **不是** URI 承载点（已纠正红队的误判）：`#EXT-X-BYTERANGE` —— 它描述的是
 * "前一条 URI 的字节区间"，本身不含地址；要求的是代理的 Range 支持能覆盖
 * "同一 URL 多次不同区间"请求（CastProxyServer 已实现）。
 *
 * 相对路径的解析基准是**清单的最终响应 URL 的所在目录**（RFC 8216 §4.1）；
 * 调用方必须传入跟随重定向后的 URL（OkHttp 的 `response.request.url`）。
 *
 * 纯函数 + 注入式 `mapUri`，因此不需要 HTTP 就能单测全部改写逻辑。
 */
object HlsPlaylistRewriter {

    /** 匹配标签行里的 URI 型属性（`URI=` / `X-ASSET-URI=` / `I-FRAME-...URI=` 都能命中） */
    private val URI_ATTRIBUTE_REGEX = Regex(
        "([A-Za-z0-9-]*URI)\\s*=\\s*(\"([^\"]*)\"|([^,\\s]*))"
    )

    /** 判定是否为 HLS 清单（Content-Type 优先，其次 URL 路径） */
    fun isPlaylist(contentType: String?, url: String?): Boolean =
        MimeSniffer.isHls(url, contentType)

    /**
     * 重写清单。
     *
     * @param content 上游返回的清单原文
     * @param playlistUrl 清单的**最终**响应 URL（用于解析相对路径）
     * @param mapUri 把"绝对上游 URI"映射为"代理路径"；由调用方登记注册表后给出
     */
    fun rewrite(content: String, playlistUrl: String, mapUri: (String) -> String): String {
        if (content.isEmpty()) return content
        val builder = StringBuilder(content.length + 256)
        val lines = content.split("\n")
        for (index in lines.indices) {
            val rawLine = lines[index]
            // 行尾的 \r 单独摘出来：改写逻辑只看内容，写回时原样补回，
            // 保证 \r\n 风格不被破坏（个别渲染端对此敏感）
            val hasCr = rawLine.endsWith("\r")
            val core = if (hasCr) rawLine.dropLast(1) else rawLine
            val rewritten = when {
                core.isBlank() -> core
                core.startsWith("#") -> rewriteTagLine(core, playlistUrl, mapUri)
                else -> mapUri(resolveAbsolute(playlistUrl, core))
            }
            builder.append(rewritten)
            if (hasCr) builder.append('\r')
            if (index != lines.lastIndex) builder.append('\n')
        }
        return builder.toString()
    }

    /** 标签行：改写行内所有 URI 型属性；没有属性则原样返回 */
    private fun rewriteTagLine(line: String, playlistUrl: String, mapUri: (String) -> String): String {
        if (!line.contains("URI=")) return line
        return URI_ATTRIBUTE_REGEX.replace(line) { match ->
            val attributeName = match.groupValues[1]
            val quoted = match.groupValues[2]
            val value = if (quoted.startsWith("\"")) match.groupValues[3] else match.groupValues[4]
            if (value.isBlank()) {
                match.value
            } else {
                val mapped = mapUri(resolveAbsolute(playlistUrl, value))
                "$attributeName=\"$mapped\""
            }
        }
    }

    /**
     * 相对路径 → 绝对。
     *
     * 用 `java.net.URL(base, relative)` 的 URL 语义（正确处理 `/绝对`、`../上级`、`./同级`），
     * 而不是字符串拼目录 —— 这正是 RFC 8216 要求的解析规则。
     * 已是绝对地址（http/https）时原样返回。
     */
    fun resolveAbsolute(playlistUrl: String, uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        return kotlin.runCatching { URL(URL(playlistUrl), trimmed).toString() }.getOrDefault(trimmed)
    }
}
