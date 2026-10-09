package io.legado.app.help.update

/**
 * 更新日志摘要（unify-changelog-and-release）。
 *
 * 把 `assets/updateLog.md`（按天、严格倒序、带 `**YYYY/MM/DD（…）**` 标题）裁剪为
 * 「上一版本 → 当前版本」区间内、**去日期**、**按分节合并**的 Markdown，
 * 供「更新版本后」弹窗展示（与发版正文口径一致：用户只关心本版本相对上一版本的优化）。
 *
 * 纯函数、零 Android 依赖（可 JVM 单测）。**不做**发版侧的 C2 去重 / C3 事务剔除 /
 * C4 对比截断 / C5 超长压缩（那是发版权威清洗；此处仅做展示裁剪，见 spec Drawbacks）。
 */
object UpdateLogDigest {

    /** 日期块标题：`**2026/10/09（优化）**` */
    private val DATE_HEAD = Regex("""\*\*(\d{4}/\d{2}/\d{2})""")

    /** 版本名：`3.26.100911`（YY=26 → 2026、MMDDHH=100911） */
    private val VERSION = Regex("""^3\.(\d{2})\.(\d{2})(\d{2})""")

    /** 输出层分节规范顺序（与 `scripts/publish_release.py::SECTION_ORDER` 一致） */
    private val SECTION_ORDER = listOf("### 新增", "### 优化", "### 修复", "### 其它")

    /**
     * 版本名 → 日期（`YYYY/MM/DD`）。无法解析返回 null。
     */
    fun parseVersionDate(versionName: String?): String? {
        val name = versionName?.trim().orEmpty()
        val m = VERSION.find(name) ?: return null
        val month = m.groupValues[2]
        val day = m.groupValues[3]
        return "20${m.groupValues[1]}/$month/$day"
    }

    /**
     * 生成更新说明。
     *
     * @param rawLog `assets/updateLog.md` 全文
     * @param fromVersionName 上一版本名（升级前的持久化值；缺失/非法时兜底「最新一天」）
     * @param toVersionName 当前版本名（`appInfo.versionName`）
     * @return 可直接交给 Markdown 弹窗的文本；无可展示内容时返回 null
     */
    fun digest(rawLog: String, fromVersionName: String?, toVersionName: String?): String? {
        val blocks = parseBlocks(rawLog)
        if (blocks.isEmpty()) return null

        val fromName = fromVersionName?.trim()
        val toName = toVersionName?.trim()
        val toDate = parseVersionDate(toName)
        val fromDate = parseVersionDate(fromName)
        val selected: List<Pair<String, String>> = when {
            toDate == null || fromDate == null -> listOf(blocks.first()) // 兜底：最新一天
            else -> blocks.filter { it.first >= fromDate && it.first <= toDate }
        }
        if (selected.isEmpty()) return null

        val sections = LinkedHashMap<String, MutableList<String>>()
        for ((_, body) in selected) {
            collectSections(body, sections)
        }
        val ordered = SECTION_ORDER.filter { sections[it]?.isNotEmpty() == true } +
            sections.keys.filter { it !in SECTION_ORDER }
        if (ordered.isEmpty()) return null

        val rendered = ordered.joinToString("\n\n") { name ->
            name + "\n" + sections.getValue(name).joinToString("\n") { "- $it" }
        }
        val header = if (fromDate != null && toDate != null) "$fromName → $toName\n\n" else ""
        return header + rendered
    }

    /** 拆 `[(日期, 块原文)]`，块含标题行本身，按文件顺序（= 日期倒序） */
    private fun parseBlocks(content: String): List<Pair<String, String>> {
        val matches = DATE_HEAD.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { i, m ->
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else content.length
            m.groupValues[1] to content.substring(start, end).trim()
        }
    }

    /** 把单个日期块的分节/条目并入 [out]（分节保持首次出现顺序；无分节的裸条目归 `### 其它`） */
    private fun collectSections(body: String, out: LinkedHashMap<String, MutableList<String>>) {
        var current: String? = null
        for (raw in body.lineSequence()) {
            val s = raw.trim()
            when {
                s.startsWith("### ") -> {
                    current = s
                    out.getOrPut(s) { mutableListOf() }
                }
                s.startsWith("- ") -> {
                    val name = current ?: "### 其它".also { out.getOrPut(it) { mutableListOf() } }
                    out.getOrPut(name) { mutableListOf() }.add(s.removePrefix("- ").trim())
                }
            }
        }
    }
}