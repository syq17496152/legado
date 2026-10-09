package io.legado.app.model.localBook.epubcore.template

/**
 * 每模板翻页方式策略（epub-md-rich-rendering 阶段 4.6）。
 *
 * 需求：**每个模板各自记忆翻页方式**；滚动模板**固定滚动**（不可覆盖）；未记忆项**跟随阅读设置**。
 * 记忆值持久化在 [ReaderTemplatePreferences]（与模板 JSON 分离——作者数据不得掺入用户偏好）。
 *
 * 纯函数 ⇒ JVM 可测。
 */
internal object ReaderTemplatePageTurnPolicy {

    /**
     * 翻页方式。取值与既有 `PageAnim` 语义对齐（`-1` 表示"跟随阅读设置"）。
     */
    enum class Mode(val value: Int) {
        /** 跟随阅读设置（默认，不记忆具体方式）。 */
        FOLLOW_READING(-1),
        COVER(0),
        COVER_LINKED(1),
        SLIDE(2),
        SIMULATION(3),
        SCROLL(4),
        NO_ANIM(5);

        companion object {
            fun fromValue(value: Int): Mode = entries.firstOrNull { it.value == value } ?: FOLLOW_READING
        }
    }

    /**
     * 解析实际生效的翻页方式。
     *
     * @param templateType 模板类型（`paged` / `scroll`）。
     * @param remembered 该模板的记忆值（无记忆传 null）。
     * @param readingSettingMode 阅读设置里的翻页方式（`FOLLOW_READING` 以外的具体值）。
     */
    fun resolve(
        templateType: String,
        remembered: Mode?,
        readingSettingMode: Mode
    ): Mode {
        // 滚动模板固定滚动：其排版本身是滚动容器，切到翻页会破坏 `data-reader-scroll-viewport` 契约
        if (templateType == EpubReaderTemplate.TYPE_SCROLL) return Mode.SCROLL
        if (remembered != null && remembered != Mode.FOLLOW_READING) return remembered
        // 无记忆或记忆为"跟随阅读设置" ⇒ 用阅读设置；阅读设置本身也是跟随 ⇒ 落到覆盖（既有默认）
        return if (readingSettingMode == Mode.FOLLOW_READING) Mode.FOLLOW_READING else readingSettingMode
    }

    /** 该模板是否允许记忆（滚动模板不允许，避免与固定滚动冲突）。 */
    fun canRemember(templateType: String): Boolean = templateType != EpubReaderTemplate.TYPE_SCROLL

    /** 记忆值的合法性归一：非法值或滚动模板 ⇒ [Mode.FOLLOW_READING]。 */
    fun normalizeForStore(templateType: String, value: Int): Mode {
        if (!canRemember(templateType)) return Mode.FOLLOW_READING
        val mode = Mode.fromValue(value)
        return if (mode == Mode.SCROLL) Mode.FOLLOW_READING else mode
    }
}