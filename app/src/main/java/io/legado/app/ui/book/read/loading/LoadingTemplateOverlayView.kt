package io.legado.app.ui.book.read.loading

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.legado.app.constant.AppLog
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.ui.book.read.epub.loading.EpubLoadingOverlay
import io.legado.app.ui.book.read.epub.loading.LoadingOverlayState
import io.legado.app.ui.book.read.epub.loading.LoadingTemplateChrome
import kotlin.math.max

/**
 * 文本阅读面（在线/离线文本小说）的「首屏加载模板」覆盖层（reader-theme-family 3.13）。
 *
 * 为什么用**独立覆盖层**而不是把模板塞进 `ReadView`：
 * 文本面的首屏是 `TextPageFactory` 按 `ReadBook.msg` 合成的文本页，绘制点分散在 `ReadView` /
 * 分页引擎内部；直接改绘制点会与既有分页逻辑耦合。独立层只做一件事 —— 在"当前无任何内容页"时
 * 叠一帧模板；**模板不可用（`draw` 返回 false）就整层透明**，底下既有文本首屏照常可见
 * ⇒ 满足"永不比改造前差"（与 EPUB 侧 [EpubLoadingOverlay] 同一不变量）。
 *
 * 绘制实现**完全复用** [EpubLoadingOverlay]（同款"无动画、重活缓存、失败即回落"三纪律），
 * 本层不新增任何绘制逻辑；取模板的**唯一入口**是 `EpubLoadingTemplateManager.selected(...)`。
 *
 * ⚠️ **不吃手势**：布局里本层不设 `clickable`/`focusable`，且不消费触摸 ⇒ 翻页/长按选择
 * 全部穿透到下面的阅读面。
 */
class LoadingTemplateOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val overlay by lazy { EpubLoadingOverlay(resources) }

    private val state = LoadingOverlayState()

    /**
     * 当前一轮的加载文案。
     *
     * 为什么**自带字段**而不扩 [LoadingOverlayState]：状态机的既有语义（模板/夜态/失败态）已被
     * EPUB 侧逐条断言；文案只在本层用，扩类会连带改 EPUB 侧断言面 ⇒ 无收益的联动风险。
     */
    private var message: String = ""

    /** 正式自证日志的"上一模式"（绘制高频调用，只在模式变化时记一条，防刷屏）。 */
    private var lastLoggedMode: String? = null

    /**
     * 注入模板并显示本层。
     *
     * `template == null` ⇒ 本层透明（`onDraw` 早退），但**仍置 VISIBLE**：可见性只表达"当前无内容页、
     * 正在加载"这件事，具体画不画由 [EpubLoadingOverlay.draw] 的返回值决定。
     */
    fun show(template: EpubLoadingTemplate?, night: Boolean, message: String) {
        state.inject(template, night)
        this.message = message
        // ⚠️ **每轮重置去重键**（2026-10-20 真机可计数化）：`show()` 是**离散事件**（每轮加载一次），
        // 不像逐帧绘制那样高频 ⇒ 每轮记一条 `loading overlay mode=...` 既不会刷屏，又能让真机**数出
        // "本轮加载到底有没有走模板"**（若沿用"仅模式变化记一条"，同一 Activity 内后续章节切换就不再有日志
        // ⇒ "切章也有首屏"这件事在真机不可证）。
        lastLoggedMode = null
        visibility = VISIBLE
        invalidate()
    }

    /**
     * 隐藏本层并**收尾一轮**（`endRound` 清模板，防模板泄漏到下一次开书 —— 与 EPUB 侧同口径）。
     */
    fun hide() {
        visibility = GONE
        state.endRound()
    }

    /** 加载文案变化（如从"加载中"变"加载失败"）：仅在本层可见时重绘。 */
    fun updateMessage(message: String) {
        if (this.message == message) return
        this.message = message
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val template = state.template
        if (template == null || visibility != VISIBLE) return
        val drewTemplate = overlay.draw(canvas, template, state.night, message, false, overlayInsets())
        logLoadingOverlayMode(drewTemplate, template)
        // 本层**唯一**绘制入口就是上面那次 `overlay.draw`：它返回 false（插画解析失败 / 尺寸非法）
        // ⇒ 本层不补画任何底色 / 文字 ⇒ 整层透明，底下既有文本首屏（`TextPageFactory` 合成页）照常可见。
        if (!drewTemplate) return
    }

    /**
     * 首屏遮罩**走了哪条路**的正式自证（与 `EpubReadView.logLoadingOverlayMode` 同口径，仅多标 `surface=text`）。
     *
     * 只在**模式变化**时记一条；三态恰好覆盖 [io.legado.app.ui.book.read.epub.loading.LoadingTemplateOverlayPolicy]
     * 的判据 ⇒ "模板机制有没有真正接管文本面首屏"可在真机上被证明（本项目失守多半是"设置了没反应"）。
     */
    private fun logLoadingOverlayMode(drewTemplate: Boolean, template: EpubLoadingTemplate?) {
        val mode = when {
            drewTemplate -> "template"
            template == null -> "legacy:no-template"
            else -> "legacy:template-unusable"
        }
        if (mode == lastLoggedMode) return
        lastLoggedMode = mode
        AppLog.putDebugWithTag(
            AppLog.TAG_READER_TEMPLATE,
            "loading overlay mode=$mode, surface=text, id=${template?.id.orEmpty()}"
        )
    }

    /**
     * 取系统栏 insets（供首屏模板避让刘海/手势条）。与 `EpubReadView.windowInsetsForOverlay` 同构：
     * 取不到（未 attach / 早期内核）⇒ `null`，由 `LoadingTemplateChrome.clampInsets` 视作全 0 再夹取。
     */
    private fun overlayInsets(): LoadingTemplateChrome.EdgeInsets? {
        val insets = ViewCompat.getRootWindowInsets(this) ?: return null
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        return LoadingTemplateChrome.EdgeInsets(
            max(bars.left, cutout.left),
            max(bars.right, cutout.right),
            max(bars.top, cutout.top),
            max(bars.bottom, cutout.bottom)
        )
    }
}
