package io.legado.app.help.rss

/**
 * 订阅正文的 **`<video>` 检测**（REQ-11 / tasks 2.2 / AD-18）。
 *
 * 用途：type=0（网页模式）订阅源取回正文后，判断正文是否含 `<video>` 标签 ⇒ 命中则自动转内置播放器，
 * 免去用户「网页模式下看不到画面」的困惑。
 *
 * 设计口径（照 design 常量，勿擅改）：
 * - [MIN_VIDEO_SCAN_LEN]：短于此长度的正文**直接跳过**（短文本不可能是视频页，省一次扫描）；
 * - [MAX_VIDEO_SCAN_LEN]：超过则**截断后再解析**（防超大页面上的正则开销）；
 * - **异常一律视为未命中**（检测永不得让正文加载失败）。
 *
 * 误判防线：只认 `<video` **标签**（要求其后为空白或 `>`），不匹配裸字符串 "video" ⇒
 * 普通文章里出现单词 video（如影评）不会触发跳转。
 */
object RssVideoDetector {

    /** 短于此长度直接跳过扫描。 */
    const val MIN_VIDEO_SCAN_LEN = 200

    /** 超过此长度截断后再解析（512KB）。 */
    const val MAX_VIDEO_SCAN_LEN = 512 * 1024

    private val videoTagRegex = Regex("""<video[\s>/]""", RegexOption.IGNORE_CASE)

    /** 解析后可能是**直链视频地址**（内容规则直接抽出播放地址，正文里没有 `<video>` 标签）。 */
    private val directVideoUrlRegex = Regex(
        """^https?://\S+\.(m3u8|mp4|flv|ts|mkv|webm|mov|avi)(\?\S*)?$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * **入口**：正文（**内容规则解析后**的输出）是否应转内置播放器。
     *
     * 用户口径（2026-09-27）：「内容规则**解析后**可能有视频标签，而不是刚开始就有的情况」——
     * 因此本检测作用于 `Rss.getContentAwait()` 返回的 **ruleContent 解析结果**，而非原始响应 HTML。
     * 解析结果有两种形态，均须覆盖：
     * ① 含 `<video>` 标签的 HTML 片段（见 [detectVideoInHtml]）；
     * ② **规则直接抽出的直链视频地址**（如 `.m3u8` / `.mp4`）——此时正文里没有任何标签，
     *    只判标签会**漏掉**这类源（这正是「刚开始没有、解析后才有」的另一半情形）。
     *
     * @param body 内容规则解析后的正文
     */
    fun detectVideoInBody(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return kotlin.runCatching {
            looksLikeDirectVideoUrl(body) || detectVideoInHtml(body)
        }.getOrDefault(false)
    }

    /**
     * 解析结果是否为**直链视频地址**（严格口径，防误判）：
     * 整串就是一个 http(s) 视频直链（≤2048 字符、不含 `<`、扩展名命中视频容器/流格式）。
     */
    fun looksLikeDirectVideoUrl(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val trimmed = body.trim()
        if (trimmed.length > 2048 || trimmed.contains('<')) return false
        return directVideoUrlRegex.matches(trimmed)
    }

    /**
     * 正文是否含 `<video>` 标签。
     *
     * @param html 订阅正文（可为 null / 空）
     * @return true = 命中（应转内置播放器）；任何异常或长度不足均返回 false
     */
    fun detectVideoInHtml(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        if (html.length < MIN_VIDEO_SCAN_LEN) return false
        return kotlin.runCatching {
            val scoped = if (html.length > MAX_VIDEO_SCAN_LEN) {
                html.substring(0, MAX_VIDEO_SCAN_LEN)
            } else {
                html
            }
            videoTagRegex.containsMatchIn(scoped)
        }.getOrDefault(false)
    }
}