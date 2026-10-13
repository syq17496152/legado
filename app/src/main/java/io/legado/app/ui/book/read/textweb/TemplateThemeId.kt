package io.legado.app.ui.book.read.textweb

/**
 * 沙箱主题 id（三态：日间 / 夜间 / 水墨屏）——**映射单源**（2026-10-14）。
 *
 * 沙箱只认 `<html data-reader-theme>` / `<html data-md-theme>` 这一对属性：模板 CSS 与 `md/` 基线
 * 各自按它们取态。取值必须由宿主按阅读器三态算出，且**只能有一处**实现——否则"模板取态"与
 * "基线取态"会漂移（症状：正文是夜间色、代码块/引用底色却是天亮态）。
 *
 * **水墨屏优先级高于夜间**：与 `HighlightPalette.Tone.EINK` 的判序一致（墨水屏设备即使系统处于夜间
 * 也按水墨屏取态），否则水墨屏会取到夜间基线色（灰阶靠抖动模拟 ⇒ 灰底会脏）。
 */
object TemplateThemeId {

    const val DAY = "day"
    const val NIGHT = "night"
    const val EINK = "eink"

    /** 三态映射；[eink] 优先于 [dark]。 */
    fun of(dark: Boolean, eink: Boolean = false): String = when {
        eink -> EINK
        dark -> NIGHT
        else -> DAY
    }
}
