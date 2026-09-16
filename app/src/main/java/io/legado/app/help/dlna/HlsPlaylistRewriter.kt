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

    /** master 清单的变体标签前缀（「流畅优先」筛选用） */
    private const val STREAM_INF_PREFIX = "#EXT-X-STREAM-INF"

    /** 变体带宽属性（「流畅优先」取最低档） */
    private val BANDWIDTH_REGEX = Regex("BANDWIDTH\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

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

    /**
     * AD-16「流畅优先」：master 清单**只保留最低 `BANDWIDTH` 变体**。
     *
     * 用途：渲染端自身的码率协商常会选到手机上行扛不住的高码率（投屏链路上手机是"下载+上传"
     * 双份流量），锁定最低档可显著降低带宽需求。**会降低画质**，故由用户开关控制
     * （`dlnaPreferSmooth`，默认开；关闭即"画质优先"，保持原多码率行为）。
     *
     * 覆盖两种变体形态（与 [rewrite] 的承载点一致）：
     *  - 标签行 + 紧随的 URI 行（最常见）
     *  - 标签行**行内** `URI="..."`（无独立 URI 行）
     *
     * 非 master 清单（无 `EXT-X-STREAM-INF`）或只有一个变体时原样返回。
     */
    fun keepLowestBandwidth(content: String): String {
        if (!content.contains(STREAM_INF_PREFIX, ignoreCase = true)) return content
        val lines = content.split("\n")
        // 每个变体块：标签行下标 + URI 行下标（行内 URI 形态时为 -1）
        val blocks = ArrayList<Pair<Int, Int>>()
        var bestBlock = -1
        var bestBandwidth = Long.MAX_VALUE
        var index = 0
        while (index < lines.size) {
            val core = lines[index].removeSuffix("\r")
            if (core.startsWith(STREAM_INF_PREFIX, ignoreCase = true)) {
                val bandwidth = BANDWIDTH_REGEX.find(core)
                    ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                val inlineUri = URI_ATTRIBUTE_REGEX.containsMatchIn(core)
                val uriIndex = if (inlineUri) {
                    -1
                } else {
                    val next = index + 1
                    if (next < lines.size && !lines[next].removeSuffix("\r").startsWith("#")) next else -1
                }
                if (bandwidth < bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestBlock = blocks.size
                }
                blocks.add(index to uriIndex)
                index = if (uriIndex >= 0) uriIndex + 1 else index + 1
            } else {
                index++
            }
        }
        if (bestBlock < 0 || blocks.size <= 1) return content
        // 丢弃除最优变体外的所有变体块（含其 URI 行）
        val dropped = HashSet<Int>()
        blocks.forEachIndexed { blockIndex, block ->
            if (blockIndex == bestBlock) return@forEachIndexed
            dropped.add(block.first)
            if (block.second >= 0) dropped.add(block.second)
        }
        return lines.filterIndexed { lineIndex, _ -> lineIndex !in dropped }.joinToString("\n")
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
