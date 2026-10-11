package io.legado.app.ui.association

/**
 * 文件关联入口判据（epub-md-rich-rendering / spec MD-01「三入口」判据层）。
 *
 * **为什么需要**：md 的导入入口有三条 —— SAF / 文件管理器 / **分享**。前两条走
 * `ACTION_VIEW`（文件句柄在 `intent.data`），而**分享走 `ACTION_SEND`，文件句柄在
 * `intent.extra.STREAM`**。宿主若只看 `data`，分享入口会**静默不导入**（表现为
 * "从文件管理器分享 .md 给本应用后毫无反应"）——这是"入口形同未接线"的典型形态，
 * 故把"从哪个字段取文件"抽成纯判据，可 JVM 断言。
 *
 * 补充事实：分享 .md 时 MIME 可能是 `text/markdown`（新式发送方）或
 * `application/octet-stream`（旧式文件管理器 MimeTypeMap 未收录 md）。MIME 决定
 * **清单过滤器是否命中**（见 AndroidManifest 的 SEND filter），而"句柄在哪个 extra"
 * 与 MIME 无关 —— 一律 `EXTRA_STREAM`。两件事分开处理，避免把"过滤没命中"误判成"句柄取错"。
 */
object AssociationIntentPolicy {

    /** 入口来源：决定从哪个字段取待处理文件。 */
    enum class Entry {
        /** 分享（`ACTION_SEND`）：文件句柄在 `intent.extra.STREAM`。 */
        SHARED_STREAM,

        /** 打开方式（`ACTION_VIEW` 及其它）：文件句柄在 `intent.data`。 */
        DATA_URI
    }

    /** 分享入口的 action 常量（避免判据层依赖 android.content.Intent 类型）。 */
    const val ACTION_SEND = "android.intent.action.SEND"

    /** 由 intent action 判定入口来源；**非 SEND 一律按 `data` 处理**（含 action 为空）。 */
    fun entryOf(action: String?): Entry =
        if (action == ACTION_SEND) Entry.SHARED_STREAM else Entry.DATA_URI

    /**
     * 入口解析结果。
     *
     * @param entry 入口来源（供埋点/诊断）。
     * @param uri 待处理文件 URI 字符串；null/空白 ⇒ 调用方必须放弃并结束（无文件可处理）。
     */
    data class Plan(val entry: Entry, val uri: String?)

    /**
     * 解析入口文件。
     *
     * 关键不变量：**分享入口在缺 `EXTRA_STREAM` 时不得回退到 `data`** ——
     * 分享场景 `data` 通常为空或指向无关内容，回退会把"没拿到文件"伪装成"拿到了别的 URI"。
     */
    fun plan(action: String?, dataUri: String?, streamUri: String?): Plan {
        val entry = entryOf(action)
        val picked = when (entry) {
            Entry.SHARED_STREAM -> streamUri
            Entry.DATA_URI -> dataUri
        }
        return Plan(entry = entry, uri = picked?.takeIf { it.isNotBlank() })
    }
}