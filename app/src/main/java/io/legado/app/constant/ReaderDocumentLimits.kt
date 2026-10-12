package io.legado.app.constant

/**
 * 「有界文档」上限的**单一来源**（epub-md-rich-rendering · AD-15 / §1.3「单章 2Mi / 20,000 blocks」· 任务 3.1 统一口径）。
 *
 * 为什么必须单源（2026-10-13 统一）：同一章正文会先后流经**多条判据链** ——
 * ①md 构建（`help/md/MdDocumentBuilder`）；②富渲染承载探测（`ui/.../TextRichContentProbe`）；
 * ③统一文档模型与其配套（`epubcore/direct/TextReaderDocument`、`TextReaderReplacementProcessor`、
 * `TextReaderSourceBubblePolicy`）。
 * 三处各自写死字面量时会出现两类**静默缺陷**：
 * - **一端通过、另一端抛出**：同一章能进阅读页却在模板面白屏（或 md 能开、富渲染面直接拒），
 *   因为两条链的上界不再相等；
 * - **改一处不同步**：调大/调小上限时只改到调用点之一 ⇒ 口径分裂且无任何测试变红。
 *
 * 放在 `constant` 包：正文域（`model/...`）、helper 域（`help/...`）、UI 承载域（`ui/...`）都可依赖它，
 * 不引入任何跨层依赖方向问题（与 `AppLog`/`PreferKey` 同层的常规做法）。
 */
object ReaderDocumentLimits {

    /** 单章正文字符上界（2Mi UTF-16；口径是**单章**，不是整本）。 */
    const val MaxSourceChars = 2 * 1024 * 1024

    /**
     * 单章**块数**上界（20k）。与 [MaxSourceChars] 是**两条独立上界**：
     * 字符数管输入体量，块数管**渲染节点规模**（2Mi 字符能写出十几万个块 ⇒ 仅靠字符上界挡不住 DOM 爆炸）。
     */
    const val MaxBlocks = 20_000
}