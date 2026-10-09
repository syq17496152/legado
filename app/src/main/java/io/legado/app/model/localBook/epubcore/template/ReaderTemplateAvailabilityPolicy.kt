package io.legado.app.model.localBook.epubcore.template

/**
 * 模板可用性策略（epub-md-rich-rendering 阶段 4.9；AD-25 / S7 / N1）。
 *
 * 回答一个**产品级**问题：此刻在该内容上，模板能不能用、能不能被用户看见、要不要提示。
 * 把这三个判断集中成纯函数，避免"入口显示但点了没反应"（N1：旗舰功能在普通 EPUB 上不生效且无提示，
 * 被误判为坏掉）。
 *
 * 判定顺序（先否定后肯定；顺序即优先级）：
 * 1. 模板系统被**整体关闭** ⇒ 不可用且**不提示**（关闭的语义是"等同于纯 EPUB 行为、无残留"，
 *    此时还弹提示就是残留）；
 * 2. 内容**非文本类**（出版 EPUB / 漫画 / 图片 / 视频 / 音频 / PDF）⇒ 不可用并提示"该格式不支持"；
 * 3. 宿主**未处于文本渲染模式** ⇒ 不可用并提示开启前置（模板是文本渲染模式的外观层）；
 * 4. 模板来自**导入**且用户**未显式确认** ⇒ 不可用并给**免责提示**（S7：默认仅内置可信）；
 * 5. 其余 ⇒ 可用。
 */
internal object ReaderTemplateAvailabilityPolicy {

    /** 模板作用范围（TPL-09）。 */
    enum class ContentKind {
        /** 在线正文 / 本地 TXT / 本地 MD —— 唯一支持模板的形态。 */
        TEXT_LIKE,

        /** 出版方控制的 EPUB（固定版式/出版方排版）。 */
        PUBLISHED_EPUB,
        MANGA,
        IMAGE,
        VIDEO,
        AUDIO,
        PDF;

        val isTextLike: Boolean get() = this == TEXT_LIKE
    }

    /** 模板来源（决定信任级别）。 */
    enum class Origin {
        BUILTIN,
        IMPORTED,
        USER_EDITED
    }

    enum class Reason {
        AVAILABLE,

        /** 模板系统整体关闭（不提示）。 */
        DISABLED,

        /** 内容形态不支持（提示不适用）。 */
        UNSUPPORTED_CONTENT,

        /** 宿主未处于文本渲染模式（提示前置条件）。 */
        NOT_TEXT_READING_MODE,

        /** 导入模板待用户显式确认（给免责提示）。 */
        NEEDS_CONFIRMATION
    }

    data class Decision(
        val usable: Boolean,
        val reason: Reason,
        /** 给用户的提示；空串 ⇒ 不打扰。 */
        val notice: String
    ) {
        /** 是否应在界面上显示模板入口（不可用且原因是"内容不支持"时应隐藏，避免误点）。 */
        val showEntry: Boolean
            get() = usable || reason == Reason.NEEDS_CONFIRMATION || reason == Reason.NOT_TEXT_READING_MODE
    }

    /** 导入/导出免责提示（compat 报告 §六.2 + D.9：导出再分发"用户自担"）。 */
    const val ImportDisclaimer =
        "模板可能包含第三方素材，仅限你本人导入后在本地使用；请自行确认素材授权，导出再分发由你自行承担责任。"
    const val ExportDisclaimer =
        "导出后分享该模板可能涉及第三方素材授权，请自行确认后再分发。"

    fun decide(
        templatesEnabled: Boolean,
        contentKind: ContentKind,
        origin: Origin,
        importConfirmed: Boolean,
        textReadingModeActive: Boolean
    ): Decision {
        if (!templatesEnabled) {
            return Decision(usable = false, reason = Reason.DISABLED, notice = "")
        }
        if (!contentKind.isTextLike) {
            return Decision(
                usable = false,
                reason = Reason.UNSUPPORTED_CONTENT,
                notice = "该格式不支持页面模板（仅作用于在线正文 / 本地 txt / 本地 md）"
            )
        }
        if (!textReadingModeActive) {
            return Decision(
                usable = false,
                reason = Reason.NOT_TEXT_READING_MODE,
                notice = "页面模板需要先开启文本渲染模式"
            )
        }
        if (origin == Origin.IMPORTED && !importConfirmed) {
            return Decision(usable = false, reason = Reason.NEEDS_CONFIRMATION, notice = ImportDisclaimer)
        }
        return Decision(usable = true, reason = Reason.AVAILABLE, notice = "")
    }

    /** 导出前提示（用于分享/导出动作的确认弹层）。 */
    fun exportNotice(origin: Origin): String = when (origin) {
        Origin.BUILTIN -> ""
        // 内置模板为自研合规素材 ⇒ 无需免责；用户模板/导入模板可能含第三方素材
        Origin.IMPORTED, Origin.USER_EDITED -> ExportDisclaimer
    }
}