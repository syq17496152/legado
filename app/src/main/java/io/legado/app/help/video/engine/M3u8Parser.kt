package io.legado.app.help.video.engine

import java.net.URI

/**
 * m3u8/HLS 清单纯函数解析器。
 *
 * video-sniff-403-and-rss-classic-fix 4.4/AD-07：从 HlsDownloader 等价下沉，
 * 播放侧（M3u8PreCheck 结构感知/Phase 4 variant 感知）与下载侧共享。
 *
 * 仅承载无网络 IO、无 Android 依赖、无全局状态的纯文本解析能力：
 * 分片解析、多码率选优、AES-128 KEY 属性解析、相对地址 resolve。
 * 解析行为与原 HlsDownloader 实现逐行等价（等价迁移，禁止逻辑变更）。
 */
object M3u8Parser {

    /**
     * #EXT-X-KEY 行的原始属性解析结果（未做支持性判定，判定逻辑由调用方承担）。
     *
     * @param method METHOD 属性值（已大写、去引号）；清单未声明 METHOD 时为 null
     * @param uri    URI 属性值（key 的相对/绝对地址）；未声明时为 null
     * @param iv     IV 属性值（16 字节）；未声明时为 null（调用方按媒体序号派生）
     */
    data class KeyInfo(
        val method: String?,
        val uri: String?,
        val iv: ByteArray?
    )

    /**
     * 多码率主清单选优：返回 BANDWIDTH 最高的码流 URI。
     *
     * 原实现来自 HlsDownloader.pickBestVariant（等价下沉）。
     */
    fun pickBestVariant(playlist: String): String? {
        val uriRegex = """^[^#].*""".toRegex()
        val lines = playlist.lineSequence().toList()
        var bestUri: String? = null
        var bestBandwidth = -1L
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // B10：剥离 AVERAGE-BANDWIDTH 干扰（否则 find() 会命中其子串取错码流）
                val bw = Regex("""(?<![A-Z-])BANDWIDTH=(\d+)""")
                    .find(line.replace("AVERAGE-BANDWIDTH", "X-BW"))?.groupValues?.get(1)
                    ?.toLongOrNull() ?: 0L
                // 下一行是非 # 开头的码流 URI
                var j = i + 1
                var candidate: String? = null
                while (j < lines.size && lines[j].trim().startsWith("#")) j++
                if (j < lines.size) candidate = lines[j].trim()
                uriRegex.matchEntire(candidate ?: "")?.let {
                    if (bw > bestBandwidth || bestUri == null) {
                        bestBandwidth = bw
                        bestUri = candidate
                    }
                }
                i = j
            } else {
                i++
            }
        }
        return bestUri
    }

    /**
     * 判定是否 master（多码率主）清单：含 #EXT-X-STREAM-INF 标签行即为 master。
     * video-sniff-403-and-rss-classic-fix Phase 4 (5.4)：播放侧 variant 感知辅助（纯文本判定，
     * 供播放侧 PROBE/未来 Exo 层在 fetch 清单文本后直接判定，无需网络 IO）。
     */
    fun isMasterPlaylist(text: String): Boolean =
        text.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

    /**
     * 从 master 清单文本中选出最优 variant 并 resolve 为绝对地址（纯文本版组合：
     * [pickBestVariant] 选 BANDWIDTH 最高码流 + [resolveRelative] 相对地址解析，
     * 供播放侧 PROBE/未来 Exo 层 fetch 后直接消费，本阶段仅落地函数+单测不强行接入播放链）。
     * 非 master（媒体清单/空文本）返回 null——调用方应将原地址按媒体清单处理。
     */
    fun pickVariantFromText(text: String, baseUrl: String): String? {
        if (!isMasterPlaylist(text)) return null
        return pickBestVariant(text)?.let { resolveRelative(baseUrl, it) }
    }

    /**
     * 解析媒体清单中的分片（#EXTINF 后跟的 URI）并累加 EXTINF 声明的总时长（毫秒）。
     *
     * 总时长用于 ts→mp4 重封装后的完整性校验（B 方案）：MediaExtractor 单轨模型在
     * 编码参数切换点可能提前截断，mp4 实际时长会显著小于清单声明的总时长，据此回退保留 ts。
     * 原实现来自 HlsDownloader.parseSegments（等价下沉）。
     */
    fun parseSegments(playlist: String): Pair<List<String>, Long> {
        val result = mutableListOf<String>()
        var totalMs = 0L
        val lines = playlist.lineSequence().toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                // #EXTINF:10.000, 或 #EXTINF:10,title，取冒号后逗号前的秒数
                Regex("""#EXTINF:\s*([0-9.]+)""").find(line)?.groupValues?.get(1)
                    ?.toDoubleOrNull()?.let { totalMs += (it * 1000).toLong() }
                var j = i + 1
                while (j < lines.size && lines[j].trim().startsWith("#")) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty() && !uri.startsWith("#")) result.add(uri)
                }
                i = j
            } else {
                i++
            }
        }
        return result to totalMs
    }

    /**
     * 相对地址 resolve：绝对地址原样返回；相对路径按 base 解析，URI 解析失败退化为 base 截断拼接。
     * 原实现来自 HlsDownloader.resolve（等价下沉）。
     */
    fun resolveRelative(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return runCatching { URI(base).resolve(path).toString() }
            .getOrDefault(trimToBase(base) + path)
    }

    private fun trimToBase(url: String): String {
        val idx = url.lastIndexOf('/')
        return if (idx >= 0) url.substring(0, idx + 1) else url
    }

    /**
     * 解析清单中的 #EXT-X-KEY 行原始属性（METHOD/URI/IV）。
     *
     * 仅做文本提取，不做支持性判定：无 KEY 行返回 null；METHOD 为 null 表示未声明或 NONE 之外的
     * 判定（NONE/非 AES-128/缺 URI）由调用方按原 HlsDownloader.parseCrypto 逻辑处理。
     * 原实现来自 HlsDownloader.parseCrypto 的纯解析部分（等价下沉，key 拉取 IO 留在下载侧）。
     */
    fun parseKeyInfo(playlist: String): KeyInfo? {
        val keyLine = playlist.lineSequence().firstOrNull { it.trim().startsWith("#EXT-X-KEY:") }
            ?: return null
        // METHOD 值兼容带引号形式：METHOD="AES-128" 也是合法清单写法
        val method = Regex("""METHOD\s*=\s*"?([^",\s]+)""").find(keyLine)?.groupValues?.get(1)
            ?.uppercase()?.removeSurrounding("\"")
        val uri = Regex("""URI\s*=\s*"([^"]+)"""").find(keyLine)?.groupValues?.get(1)
        val iv = Regex("""IV\s*=\s*0x([0-9a-fA-F]{32})""").find(keyLine)?.groupValues?.get(1)?.let { hexStr ->
            hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return KeyInfo(method, uri, iv)
    }

    /**
     * 解析 #EXT-X-MEDIA-SEQUENCE 媒体序号起点（缺省 0），供 AES-128 缺省 IV = 起点 + 分片索引。
     * 原实现来自 HlsDownloader.parseCrypto（B4 修复，等价下沉）。
     */
    fun parseMediaSequence(playlist: String): Long =
        Regex("""#EXT-X-MEDIA-SEQUENCE:\s*(\d+)""")
            .find(playlist)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}
