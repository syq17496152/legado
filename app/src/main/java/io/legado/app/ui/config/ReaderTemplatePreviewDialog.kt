package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.model.reader.render.ChapterRef
import io.legado.app.model.reader.render.ReadingPosition
import io.legado.app.model.reader.render.RenderConfig
import io.legado.app.model.reader.render.RenderFailure
import io.legado.app.model.reader.render.RenderHost
import io.legado.app.model.reader.render.RenderToken
import io.legado.app.model.reader.render.TextRange
import io.legado.app.ui.book.read.textweb.TemplateRenderBackend
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.spToPx
import java.util.Calendar

/**
 * 模板预览弹窗（epub-md-rich-rendering 阶段 **4.8b**）。
 *
 * 核心口径：**预览必须走正式渲染链路**（`TemplateRenderBackend` + 模板沙箱 + 消息桥 + 自研分页），
 * 而不是另画一张静态示意图——后者必然与真实排版漂移，用户"预览很好看、套上却不一样"。
 * 因此本弹窗只做两件事：①把待预览模板塞进 [TemplateRenderBackend]；②给一段示例章节与示例真值。
 *
 * UI 归属（`ui-design.md` §二 U2 / K1 取色归属三步）：
 * - 弹层取色**只能**走 `rememberAppDialogStyle`（[AppDialogFrame] 内已单源），本文件零硬编码色；
 * - 复用既有组件族（`AppDialogFrame` + `LegadoMiuixActionButton`），**不新增组件族成员**；
 * - `LegadoTheme` 必须在 ComposeView 内自持根作用域（G-37）。
 *
 * 两个易失守点（都有对症处理）：
 * - `AppDialogFrame(scrollContent = false)` 必须关掉外层滚动：否则外层抢走拖动手势，
 *   **滚动模板预览将无法上下滑动**（症状：用户以为模板不可滚动）；
 * - 渲染必须等 WebView **量到尺寸之后**再发起：沙箱在 init 时以自身视口高算页框，
 *   尺寸为 0 时页框会算成 0 ⇒ 页数恒 1（与阅读页同一类失真）。
 */
class ReaderTemplatePreviewDialog : ComposeDialogFragment() {

    private var template: EpubReaderTemplate? = null
    private var backend: TemplateRenderBackend? = null
    private var renderStarted = false
    private val pageInfoState = mutableStateOf("")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val parsed = runCatching {
            EpubReaderTemplate.fromJson(arguments?.getString(ARG_TEMPLATE_JSON).orEmpty())
        }.getOrNull()
        template = parsed
        val title = parsed?.name?.takeIf { it.isNotBlank() }?.let { "预览 · $it" } ?: "模板预览"
        val engine = parsed?.let(::createBackend)
        backend = engine
        if (engine == null) {
            // 解析失败必须显式告知：空对话框与"模板没内容"分不开
            pageInfoState.value = "模板数据无效，无法预览"
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val palette = style.toMiuixPalette()
                    // 预览面高度随屏幕自适应：固定 380dp 在小屏上正文只剩一两行（"正文区要大"是用户硬要求），
                    // 取屏高的 55% 并夹在 [280, 520]dp ⇒ 接近真实阅读区的观感
                    val surfaceHeight = (
                        LocalConfiguration.current.screenHeightDp * PreviewScreenFraction
                        ).coerceIn(PreviewMinHeightDp, PreviewMaxHeightDp).dp
                    AppDialogFrame(
                        title = title,
                        message = "示例章节按正式渲染链路预演：滚动模板可直接上下滑动，分页模板用下方按钮翻页。",
                        scrollContent = false,
                        content = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                AndroidView(
                                    factory = { context ->
                                        // ⚠️ AndroidView 的 factory 入参是 Context（不是父 View）；
                                        // 承载面由后端提供（同一 WebView 实例喂给渲染链路）
                                        val surface = engine?.view() ?: View(context)
                                        renderWhenSized(surface)
                                        surface
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(surfaceHeight)
                                )
                                val info = pageInfoState.value
                                if (info.isNotBlank()) {
                                    Text(
                                        text = info,
                                        color = style.secondaryText,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = "上一页",
                                palette = palette,
                                onClick = { backend?.pagePrev() },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = "下一页",
                                palette = palette,
                                onClick = { backend?.pageNext() },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = "关闭",
                                palette = palette,
                                onClick = { dismissAllowingStateLoss() },
                                cornerRadius = style.actionRadius
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        backend?.dispose()
        backend = null
        renderStarted = false
        super.onDestroyView()
    }

    private fun createBackend(template: EpubReaderTemplate): TemplateRenderBackend {
        val engine = TemplateRenderBackend(
            context = requireContext(),
            templateProvider = { this.template },
            chapterProvider = { ReaderTemplatePreviewSample.chapter() },
            valuesProvider = { ReaderTemplatePreviewSample.values(AppConfig.isNightTheme) },
            container = {}
        )
        engine.attach(object : RenderHost {
            override fun onRendered(token: RenderToken, chapterIndex: Int, pageCount: Int) =
                updatePageInfo(pageCount)

            override fun onReMeasured(token: RenderToken, chapterIndex: Int, pageCount: Int) =
                updatePageInfo(pageCount)

            override fun onSelection(range: TextRange?) = Unit

            override fun onProgress(position: ReadingPosition) = Unit

            override fun onError(token: RenderToken?, failure: RenderFailure, error: Throwable?) {
                // 预览失败必须显式告知：否则用户看到空白对话框，与"模板本身没内容"分不开
                pageInfoState.value = "预览渲染失败：${failure.name}"
            }
        })
        return engine
    }

    private fun updatePageInfo(pageCount: Int) {
        val index = (backend?.exportPosition() as? ReadingPosition.Page)?.pageIndex ?: 0
        pageInfoState.value = "第 ${index + 1} / ${pageCount.coerceAtLeast(1)} 页"
    }

    private fun renderWhenSized(view: View, attempt: Int = 0) {
        if (renderStarted) return
        if (view.width > 0 && view.height > 0) {
            renderStarted = true
            renderPreview(view)
            return
        }
        if (attempt >= MaxSizeAttempts) {
            renderStarted = true
            renderPreview(view)
            return
        }
        view.postDelayed({ renderWhenSized(view, attempt + 1) }, SizeRetryDelayMillis)
    }

    private fun renderPreview(view: View) {
        val engine = backend ?: return
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        // 与阅读页同口径取排版参数 ⇒ 预览所见即所得（字号/行高/字色都跟随用户当前阅读设置）
        val sizePx = ReadBookConfig.textSize.toFloat().spToPx().coerceAtLeast(1f)
        engine.render(
            chapter = ChapterRef(chapterIndex = 0, chapterHref = PreviewChapterHref, title = "样例章节"),
            position = null,
            config = RenderConfig(
                pageWidthPx = width,
                pageHeightPx = height,
                fontSizePx = sizePx,
                lineHeightPx = (sizePx + ReadBookConfig.lineSpacingExtra).coerceAtLeast(1f),
                textColor = ReadBookConfig.textColor
            )
        )
    }

    companion object {
        private const val ARG_TEMPLATE_JSON = "templateJson"
        private const val PreviewChapterHref = "preview"
        private const val MaxSizeAttempts = 20
        private const val SizeRetryDelayMillis = 32L

        /** 预览面高度 = 屏高 × 该系数（夹在 [min,max] dp）：太小则正文只剩一两行，太大则弹窗超出屏。 */
        private const val PreviewScreenFraction = 0.55f
        private const val PreviewMinHeightDp = 280f
        private const val PreviewMaxHeightDp = 520f

        /** 预览入口（4.8b）：把模板序列化进参数，避免弹窗再去读目录（IO 与 UI 解耦）。 */
        fun create(template: EpubReaderTemplate): ReaderTemplatePreviewDialog =
            ReaderTemplatePreviewDialog().apply {
                arguments = bundleOf(ARG_TEMPLATE_JSON to template.toJson())
            }
    }
}

/**
 * 预览**示例章节**（纯值，故可在 JVM 逐项断言）。
 *
 * 为什么示例里必须带 mermaid/公式/代码：模板预览最容易被"看起来正常但富渲染丢了"骗过——
 * 示例正文含富渲染元素时，预览才验证了「模板 × 富渲染注入」这条真实组合路径。
 */
internal object ReaderTemplatePreviewSample {

    // ⚠️ 用普通字符串而非 raw string：示例含 `$E = mc^2$`（KaTeX 定界符），raw string 里 `$` 会被当成模板插值
    private val SampleHtml: String = buildString {
        append("<h2 class=\"reader-chapter-title\">第一章 · 样例章节</h2>")
        append("<p class=\"reader-paragraph\">这是模板预览用的示例正文：预览与阅读页走同一条渲染链路，")
        append("因此这里看到的排版就是实际阅读时的样子。</p>")
        append("<pre class=\"mermaid\">graph TD\n  A[开始] --> B{判断}\n")
        append("  B -->|是| C[执行]\n  B -->|否| D[结束]</pre>")
        append("<p>行内公式 \$E = mc^2\$ 与块级公式：</p>")
        append("<p>\$\$\\int_0^1 x^2 dx = \\frac{1}{3}\$\$</p>")
        append("<p class=\"reader-paragraph\">示例段落二：拖动可查看滚动模板的长文效果；")
        append("分页模板可用下方按钮翻页，页数由模板沙箱回传。</p>")
        append("<p class=\"reader-paragraph\">示例段落三：模板只决定外观层（页眉、页脚、底纹、边框），")
        append("正文元素基线仍由引擎提供，因此换模板不会改变内容结构。</p>")
        append("<p class=\"reader-paragraph\">示例段落四：模板若未声明正文槽位，")
        append("预览会明确报错而不是白屏——这与正式阅读路径的降级策略一致。</p>")
    }

    fun chapter(): TemplateRenderBackend.Chapter = TemplateRenderBackend.Chapter(
        html = SampleHtml,
        hasMermaid = true,
        hasMath = true
    )

    fun values(dark: Boolean, hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) =
        TemplateRenderBackend.ReaderValues(
            dark = dark,
            bookName = "示例书名",
            chapterTitle = "第一章 · 样例章节",
            progressPercent = 12,
            hour = hour
        )
}