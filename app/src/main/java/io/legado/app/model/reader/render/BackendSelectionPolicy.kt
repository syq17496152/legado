package io.legado.app.model.reader.render

/**
 * 后端分流判据（epub-md-rich-rendering 阶段 2.1 / AD-30「统一分流判据」；blueprint §二）。
 *
 * 与 [io.legado.app.model.localBook.epubcore.direct.EpubReaderContentModePolicy] 的分工：
 * - 本判据回答「**用哪个后端**」（粗粒度、决定渲染引擎）；
 * - 内容态策略回答「**该后端内怎么渲染**」（细粒度、决定是否保真/单页）。
 * 两者同源（都读同一份五态分类结果），避免「两套判据打架」（审计 A3）。
 *
 * 纯函数 ⇒ JVM 可测；`hasRichRenderElements` 由构建期标注（**不靠运行时嗅探**）。
 */
object BackendSelectionPolicy {

    /** 引擎开关（唯一开关；默认 [Engine.AUTO]）。 */
    enum class Engine {
        /** 按内容自动分流（默认）。 */
        AUTO,

        /** 强制纯文本归一化（一键回退，X11）。 */
        TEXT
    }

    enum class Backend {
        CANVAS,
        DIRECT_WEB,
        TEXT
    }

    /** 书级一致性：决定「多数章节是否出版方控制型」。 */
    data class BookConsistency(
        val publisherControlledChapters: Int,
        val totalChapters: Int
    ) {
        /** 多数（>50%）为出版方控制型 ⇒ 全书走 WebView，避免章节间样式割裂（SP-03/EPUB-13）。 */
        val mostlyPublisherControlled: Boolean
            get() = totalChapters > 0 && publisherControlledChapters * 2 > totalChapters
    }

    /**
     * 分流判定。
     *
     * 优先级（与 blueprint §二 一致）：
     * 1. 含富渲染元素（mermaid/公式/内嵌 HTML）⇒ **强制 WebView**（防 md 落 canvas 丢 mermaid，A3）；
     * 2. 显式 `TEXT` 引擎 ⇒ 纯文本；
     * 3. 书级一致性：多数章节出版方控制型 ⇒ WebView；
     * 4. 内容态 `REFLOWABLE` ⇒ canvas 快路径（AD-15）；
     * 5. 其余 ⇒ WebView。
     */
    fun select(
        hasRichRenderElements: Boolean,
        engine: Engine,
        contentMode: ContentMode,
        bookConsistency: BookConsistency? = null
    ): Backend {
        if (hasRichRenderElements) return Backend.DIRECT_WEB
        if (engine == Engine.TEXT) return Backend.TEXT
        if (bookConsistency?.mostlyPublisherControlled == true) return Backend.DIRECT_WEB
        return when (contentMode) {
            ContentMode.REFLOWABLE -> Backend.CANVAS
            else -> Backend.DIRECT_WEB
        }
    }

    /**
     * 内容态（与五态分类一一对应；**本层不复用 epubcore 枚举**，避免 model.reader 依赖 epubcore）。
     */
    enum class ContentMode {
        REFLOWABLE,
        PUBLISHER_STYLED,
        FIXED,
        MEDIA,
        INTERACTIVE
    }
}