package io.legado.app.help.dlna

/**
 * add-dlna-cast：投递媒体的 MIME / protocolInfo 推断（design AD-09）。
 *
 * 三级推断（顺序固定，前一级命中即返回）：
 *   ① URL 扩展名映射表（纯函数，**零网络开销**，覆盖绝大多数源）
 *   ② 上游 `HEAD` 的 `Content-Type`（必须带会话 headers，失败即放弃，不阻塞投屏）
 *   ③ 兜底 [DlnaConstants.MIME_FALLBACK]
 */
object MimeSniffer {

    /** 扩展名 → MIME。键一律小写、不含点。 */
    private val EXTENSION_MIME: Map<String, String> = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "3gp" to "video/3gpp",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "rmvb" to "application/vnd.rn-realmedia-vbr",
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml"
    )

    /** 判定 HLS 的 Content-Type 白名单（大小写不敏感比较） */
    private val HLS_CONTENT_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl"
    )

    /**
     * 从 URL 推断 MIME（纯函数）。
     *
     * 处理要点：先剥掉 query 与 fragment（`a.mp4?token=xxx` 不能因为 query 落空），
     * 再取最后一段路径的扩展名并小写化（`A.MP4` 要能命中）。
     */
    fun mimeFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = url.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0 || dot == lastSegment.length - 1) return null
        val ext = lastSegment.substring(dot + 1).lowercase()
        return EXTENSION_MIME[ext]
    }

    /** URL 路径末段是否为 `.m3u8`（投递判定用） */
    fun isHlsUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('/').endsWith(".m3u8", ignoreCase = true)
    }

    /** Content-Type 是否表示 HLS */
    fun isHlsContentType(contentType: String?): Boolean {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return normalized in HLS_CONTENT_TYPES
    }

    /** 综合判定是否为 HLS（Content-Type 优先，其次 URL 路径） */
    fun isHls(url: String?, contentType: String?): Boolean =
        isHlsContentType(contentType) || isHlsUrl(url)

    /** 组装 protocolInfo（第 4 段声明支持字节级 seek） */
    fun protocolInfo(mime: String): String = DlnaConstants.protocolInfo(mime)

    /**
     * 按三级顺序解析最终 MIME。
     *
     * @param url 投递地址（或原始地址）
     * @param contentProbe 第二级探测：返回上游 `Content-Type`；**允许返回 null 或抛异常**，
     *                     任一情况都静默降级，绝不因探测失败而阻断投屏。
     */
    fun resolve(url: String?, contentProbe: (() -> String?)? = null): String {
        mimeFromUrl(url)?.let { return it }
        if (contentProbe != null) {
            kotlin.runCatching { contentProbe() }
                .getOrNull()
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.contains('/') }
                ?.let { return it.lowercase() }
        }
        return DlnaConstants.MIME_FALLBACK
    }
}
