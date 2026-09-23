package io.legado.app.ui.main.bookshelf.compose

/**
 * R16（B3）书架未读颜色强调。
 *
 * 需求：开关开启后，**未读**书籍标题用主题强调色渲染；已读书籍不变；开关默认关 ⇒ 渲染与现状一致。
 *
 * 单点决策函数（纯函数、可单测），网格与列表两种布局共用，避免两处各写一份条件：
 * 口径分裂会造成「同开关下网格与列表表现不一致」的静默缺陷。
 */
internal object BookshelfUnreadEmphasis {

    /**
     * @param enabled 配置开关（默认 false）
     * @param unreadCount 该书未读章节数（≤0 = 已读完）
     * @param accent 主题强调色（`ThemeStore.accentColor`；E-Ink 下为黑、夜间/日间由主题成对提供 ⇒ 可读性由主题保证）
     * @param primaryText 主文字色（现状值）
     */
    fun titleColor(enabled: Boolean, unreadCount: Int, accent: Int, primaryText: Int): Int {
        return if (enabled && unreadCount > 0) accent else primaryText
    }
}