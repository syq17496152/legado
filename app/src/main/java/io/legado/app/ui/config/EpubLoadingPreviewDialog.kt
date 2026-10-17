package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.os.bundleOf
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.help.config.EpubLoadingTemplateManager
import io.legado.app.help.config.decodeLoadingPayload
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import org.json.JSONObject

/**
 * 首屏加载模板预览弹窗（Phase 3）。
 *
 * 骨架照 `ReaderTemplatePreviewDialog`（`ComposeDialogFragment` + `AppDialogFrame` + `AndroidView`
 * + `LegadoTheme` 根作用域 + `rememberAppDialogStyle`），内容改由 [EpubLoadingPreviewView]
 * 承载**真实绘制器** [io.legado.app.ui.book.read.epub.loading.EpubLoadingOverlay]。
 *
 * - 取色只走 `rememberAppDialogStyle`（弹层框架内单源），本文件零硬编码色；
 * - 复用既有组件族（`AppDialogFrame` + `LegadoMiuixActionButton`），**不新增组件族成员**；
 * - 模板序列化进参数（走 `EpubLoadingTemplateManager.export`）：解析失败时**显式提示**而非空白弹窗。
 */
class EpubLoadingPreviewDialog : ComposeDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val json = arguments?.getString(ARG_TEMPLATE_JSON).orEmpty()
        // 载荷解码走既有单源（校验 type/version/白名单），id 用于还原内置/自定义判别
        val template = runCatching {
            val id = JSONObject(json).optString("id")
            decodeLoadingPayload(json).getOrThrow().toTemplate(id)
        }.getOrNull()
        val title = template?.name?.takeIf { it.isNotBlank() }?.let { "预览 · $it" } ?: "首屏加载模板预览"
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val palette = style.toMiuixPalette()
                    var night by remember { mutableStateOf(AppConfig.isNightTheme) }
                    // 预览面高度随屏高自适应并夹取（与阅读页模板预览同口径）：小屏不压扁、大屏不溢出
                    val surfaceHeight = (
                        LocalConfiguration.current.screenHeightDp * PreviewScreenFraction
                        ).coerceIn(PreviewMinHeightDp, PreviewMaxHeightDp).dp
                    AppDialogFrame(
                        title = title,
                        message = if (template == null) {
                            "模板数据无效，无法预览"
                        } else {
                            "预览使用与开书首屏相同的绘制器，可切换日间 / 夜间查看配色。"
                        },
                        scrollContent = false,
                        content = {
                            if (template != null) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    AndroidView(
                                        factory = { context -> EpubLoadingPreviewView(context) },
                                        update = { view ->
                                            view.template = template
                                            view.night = night
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(surfaceHeight)
                                    )
                                }
                            }
                        },
                        actions = {
                            LegadoMiuixActionButton(
                                text = if (night) "切换到日间" else "切换到夜间",
                                palette = palette,
                                onClick = { night = !night },
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

    companion object {
        private const val ARG_TEMPLATE_JSON = "loadingTemplateJson"
        private const val PreviewScreenFraction = 0.55f
        private const val PreviewMinHeightDp = 280f
        private const val PreviewMaxHeightDp = 520f

        /** 预览入口：模板序列化进参数（含 id），弹窗侧用既有载荷解码单源还原。 */
        fun create(template: EpubLoadingTemplate): EpubLoadingPreviewDialog =
            EpubLoadingPreviewDialog().apply {
                arguments = bundleOf(ARG_TEMPLATE_JSON to EpubLoadingTemplateManager.export(template))
            }
    }
}
