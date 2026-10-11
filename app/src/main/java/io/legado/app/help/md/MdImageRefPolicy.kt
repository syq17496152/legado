package io.legado.app.help.md

/**
 * Markdown 图片引用判定策略（epub-md-rich-rendering 补充任务 S3「md 生态补齐」①）。
 *
 * **纯函数**（无 Android 依赖）⇒ 决策矩阵可在 JVM 逐格断言，IO 与平台差异只在
 * [MdRelativeImageInliner] 里处理。
 *
 * 为什么必须内联成 `data:` URI：md 正文的两个承载面都**拿不到文档目录作为解析基准**——
 * 文本富渲染面 `loadDataWithBaseURL(baseUrl="about:blank")`；模板沙箱是 `allow-scripts`
 * 的**跨源** iframe（相对路径一律解析失败）。⇒ `![x](./img.png)` 必然裂图。
 * 内联后三条路径都能显示：富渲染 WebView 面、模板沙箱面、canvas 的 `<usehtml>` 面
 * （后者由 `AnalyzeUrl.getByteArrayIfDataUri` 负责解码 data URI）。
 *
 * 边界（逐条有单测）：
 * - 只认**已知图片扩展名**：不把任意文件塞进 `data:`（避免把 md 里误写的路径读进来）；
 * - 只认**相对路径**或显式 `file:`；`http(s)://` 仍走网络（否则会把线上正文的图片误当本地文件）；
 * - `..` 逃出文档目录 ⇒ 拒（目录穿越防护）；
 * - 单图与单文档总量双上限 ⇒ 一章 HTML 不被图片撑爆。
 */
object MdImageRefPolicy {

    /** 单张图片内联上限（超限不内联，保留原始引用 ⇒ 与今天行为一致，不产生新裂图）。 */
    const val MaxInlineBytes = 2 * 1024 * 1024

    /** 同一份文档的内联总量上限（含缓存）⇒ 约束常驻内存。 */
    const val MaxInlineTotalBytes = 8 * 1024 * 1024

    /** 目录穿越时应抛出的深度：`TreeDir` 下按文件名查找的最大深度 / 最大查找数。 */
    const val TreeSearchDepth = 2
    const val TreeSearchMaxFinds = 50

    private val SchemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):")

    private val MimeByExt = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "jpe" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "avif" to "image/avif",
        "ico" to "image/x-icon"
    )

    /**
     * 是否值得尝试内联。
     *
     * 放行：相对路径（`./a.png` / `img/a.png`）与显式 `file:` / 绝对文件路径。
     * 拒绝：空串、`data:`（已内联）、`http(s):` 等网络协议、协议相对 `//host/a.png`、`content:`。
     */
    fun isInlineCandidate(src: String): Boolean {
        val trimmed = src.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("//")) return false
        val scheme = SchemeRegex.find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) return true
        return scheme == "file"
    }

    /** 去掉查询串与片段（`a.png?x=1#f` → `a.png`）：本地文件查找不该带上它们。 */
    fun stripQueryAndFragment(src: String): String {
        val cut = src.indexOfFirst { it == '?' || it == '#' }
        return if (cut < 0) src else src.substring(0, cut)
    }

    /**
     * 百分号解码（只处理 `%XX`；**不做** `+`→空格 —— 路径里的 `+` 是字面量）。
     *
     * ⚠️ 连续的 `%XX` 必须**成组按 UTF-8 解码**：中文路径一个字符占 3 个转义字节
     * （`%E5%9B%BE` = 图），逐字节 `toChar()` 会解出乱码（实测）。
     */
    fun percentDecode(src: String): String {
        if (!src.contains('%')) return src
        val out = StringBuilder(src.length)
        val pending = StringBuilder()
        var index = 0
        while (index < src.length) {
            val char = src[index]
            if (char == '%' && index + 3 <= src.length) {
                val code = src.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    pending.append(code.toChar())
                    index += 3
                    continue
                }
            }
            // 非转义字符：先把挂起的字节按 UTF-8 解出来（保证多字节序列成组）
            flushPending(out, pending)
            out.append(char)
            index++
        }
        flushPending(out, pending)
        return out.toString()
    }

    private fun flushPending(out: StringBuilder, pending: StringBuilder) {
        if (pending.isEmpty()) return
        val bytes = ByteArray(pending.length) { pending[it].code.toByte() }
        out.append(String(bytes, Charsets.UTF_8))
        pending.setLength(0)
    }

    /** 文件名（最后一个 `/` 之后；无 `/` 即自身）。 */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /** 是否绝对文件路径（`/data/x.png`）：不按文档目录解析，直接当文件路径读。 */
    fun isAbsoluteFilePath(src: String): Boolean = src.startsWith("/")

    /**
     * `file:` URI → 文件系统路径（`file:///a/b` → `/a/b`）；非 `file:` 协议原样返回。
     *
     * 为什么要这一步：`file:///a/b` 若不剥协议，会被 `normalizeRelative` 当成相对路径
     * （首段 `file:` 会被当成目录名）⇒ 拼出错误路径、静默读不到文件。
     */
    fun toFilePath(src: String): String {
        if (!src.startsWith("file:", ignoreCase = true)) return src
        val rest = src.substring("file:".length)
        return if (rest.startsWith("///")) rest.substring(2) else rest
    }

    /** 已知图片扩展名 → MIME；未知/无扩展名返回 null（⇒ 不内联）。 */
    fun mimeFor(name: String): String? = MimeByExt[name.substringAfterLast('.', "").lowercase()]

    /**
     * 归一化相对路径：解析 `.` / `..` 段。
     *
     * @return `/` 分隔的归一化相对路径；**逃出根目录**（`..` 越界）或归一化后为空时返回 null。
     */
    fun normalizeRelative(src: String): String? {
        val out = ArrayList<String>()
        src.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out.add(segment)
            }
        }
        return if (out.isEmpty()) null else out.joinToString("/")
    }

    /** 内联产物（`data:` URI）构造单源。 */
    fun dataUri(mime: String, base64: String): String = "data:$mime;base64,$base64"
}