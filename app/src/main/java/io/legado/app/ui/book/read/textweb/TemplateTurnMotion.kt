package io.legado.app.ui.book.read.textweb

import io.legado.app.constant.PageAnim

/**
 * 模板面**翻页过渡**的决策单源（epub-md-rich-rendering 阶段 4.20）。
 *
 * ### 为什么需要它
 * 模板面是**单个**跨源 WebView：内容在沙箱里按页结算，宿主只能下发 `goto-page`
 * ⇒ 翻页天然是**瞬时跳变**（用户报「页面动画呢？」）。而 archive 的普通阅读面有完整的
 * 翻页体系（`cover / slide / simulation / linkedCover / scroll / none`，见 `ReadBook.pageAnim()`）。
 *
 * 本对象把「用户选的翻页档位 → 模板面**实际可做**的过渡形态」收口成**纯函数**，
 * 使策略可被 JVM 单测逐档钉住（真机只负责验证观感，不负责验证映射对不对）。
 *
 * ### 能实现什么、为什么降级
 * 模板面只有**一层**可绘内容，所以：
 * | 档位 | archive 语义 | 模板面落地 |
 * |---|---|---|
 * | `cover` | 旧页覆盖在新页上翻走 | **COVER**：旧页快照整幅滑出，新页静止 ⇒ 语义等价 ✅ |
 * | `linkedCover` | 带视差的 cover | **LINKED_COVER**：同上但时长更长（视差用时长差近似） |
 * | `slide` | 旧页与新页联动滑动 | **SLIDE**：旧页快照滑出的同时把新页从反方向推入（位移差即为"联动"） |
 * | `simulation` | 卷曲仿真 | **COVER**：单层 WebView 画不出卷曲的纸张曲面，降级为覆盖滑出（观感方向一致） |
 * | `none` | 无过渡 | **NONE**：直接切页（尊重用户"不要动画"的选择） |
 * | `scroll` | 整章连续滚动 | **NONE**：模板面本身即**分页**（无滚动容器语义），不制造假滚动 |
 *
 * 时长取 archive 同量级（300~400ms）：太短会像硬切，太长会让连点翻页排队。
 */
object TemplateTurnMotion {

    /** 过渡形态（宿主据此决定"快照怎么走、下层动不动"）。 */
    enum class Style {
        /** 不放快照，直接切页。 */
        NONE,

        /** 旧页快照整幅滑出，下层新页静止。 */
        COVER,

        /** 旧页快照滑出 + 下层新页从反方向推入。 */
        SLIDE,

        /** 与 [COVER] 同为覆盖滑出，但时长更长（近似 archive 的视差感）。 */
        LINKED_COVER
    }

    data class Spec(val style: Style, val durationMs: Long) {
        val hasOverlay: Boolean get() = style != Style.NONE
    }

    /** 常规时长（毫秒）——与 archive `slide`/`cover` 同量级。 */
    const val DurationMillis = 300L

    /** `linkedCover` 的加长时长（毫秒）——更慢才有"联动"的观感。 */
    const val LinkedDurationMillis = 400L

    /**
     * 旧页快照的**移出位移**（像素）：`direction=+1`（下一页）时旧页**向左**移出。
     *
     * ⚠️ 方向语义必须与"翻页方向"一致（2026-10-14 用户实测投诉「点击右下角是上一页」）：
     * 页码确实前进了，但若旧页**向右**滑出，观感就是"翻回上一页" ⇒ 用户会认定方向反了。
     * 故本函数与 [incomingShift] 一起收口"方向→位移"的映射，使该语义可被 JVM 单测逐值钉住。
     */
    fun overlayShift(direction: Int, width: Int): Float = -direction * width.toFloat()

    /** 联动（SLIDE）时**下层新页**的起始位移：与旧页反向（新页自对侧推入，结束归 0）。 */
    fun incomingShift(direction: Int, width: Int): Float = direction * width.toFloat()

    /**
     * 档位 → 过渡规格（**唯一映射处**）。
     *
     * @param pageAnim 取值来自 `io.legado.app.constant.PageAnim`（调用方须传已按水墨屏收敛后的档位，
     *                 即与 `ReadBook.pageAnim()` 同源的那一个）。
     */
    fun specOf(@PageAnim.Anim pageAnim: Int): Spec = when (pageAnim) {
        PageAnim.noAnim -> Spec(Style.NONE, 0L)
        PageAnim.scrollPageAnim -> Spec(Style.NONE, 0L)
        PageAnim.slidePageAnim -> Spec(Style.SLIDE, DurationMillis)
        PageAnim.linkedCoverPageAnim -> Spec(Style.LINKED_COVER, LinkedDurationMillis)
        // 卷曲仿真在"单层 WebView"上无法呈现纸张曲面 ⇒ 降级为覆盖滑出（观感方向一致，
        // 不谎称做到了 simulation）。若将来模板面改为多层合成，可在此独立接线。
        PageAnim.simulationPageAnim -> Spec(Style.COVER, DurationMillis)
        else -> Spec(Style.COVER, DurationMillis)
    }
}
