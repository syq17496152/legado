package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.ai.AiPurifyHelper
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 文本净化结果弹框（Q-N4）。
 *
 * 交互：进入即发起净化（无重复点击成本）→ 展示净化结果 + 改动统计 + 风险提示 → 「复制」/「关闭」。
 * 为什么不提供「应用到正文」：选区坐标属于**渲染后文本**，与存储正文坐标系不一致，
 * 直接替换会错位（改错位置比不做更糟）⇒ 本版只给复制；升级路径见 [AiPurifyHelper] 类注释。
 */
class AiPurifyDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var loading by mutableStateOf(true)
    private var failure by mutableStateOf<String?>(null)
    private var result by mutableStateOf<AiPurifyHelper.PurifyResult?>(null)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val source = arguments?.getString(ARG_TEXT).orEmpty()
        startPurify(source)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val palette = style.toMiuixPalette()
                val current = result
                AppDialogFrame(
                    title = stringResource(R.string.ai_purify_title),
                    scrollContent = false,
                    content = {
                        when {
                            loading -> PurifyLoading(style)
                            failure != null -> Text(
                                text = failure.orEmpty(),
                                color = style.primaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontFamily = style.bodyFontFamily
                            )

                            current != null -> PurifyBody(current, style)
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.copy_text),
                            palette = palette,
                            enabled = current != null,
                            onClick = {
                                current?.let { requireContext().sendToClip(it.purified) }
                                dismissAllowingStateLoss()
                            },
                            cornerRadius = style.actionRadius
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.close),
                            palette = palette,
                            primary = true,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    private fun startPurify(source: String) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { AiPurifyHelper.purify(source) }
            }
            loading = false
            result = outcome.getOrNull()
            failure = outcome.exceptionOrNull()?.let {
                getString(R.string.ai_purify_failed, it.localizedMessage ?: "Error")
            }
        }
    }

    companion object {
        private const val ARG_TEXT = "sourceText"

        fun create(sourceText: String): AiPurifyDialog = AiPurifyDialog().apply {
            arguments = Bundle().apply { putString(ARG_TEXT, sourceText) }
        }
    }
}

@Composable
private fun PurifyLoading(style: AppDialogStyle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = style.accent)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.ai_purify_running),
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = style.bodyFontFamily
            )
        }
    }
}

@Composable
private fun PurifyBody(
    result: AiPurifyHelper.PurifyResult,
    style: AppDialogStyle
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (result.changed) {
                stringResource(
                    R.string.ai_purify_changed_summary,
                    result.diff.removed,
                    result.diff.added
                )
            } else {
                stringResource(R.string.ai_purify_unchanged)
            },
            color = if (result.changed) style.accent else style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontFamily = style.bodyFontFamily
        )
        if (result.changed && !result.canAutoApply) {
            Text(
                text = stringResource(R.string.ai_purify_risk_added),
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = style.bodyFontFamily
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Text(
                    text = result.purified,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.Normal,
                    fontFamily = style.bodyFontFamily
                )
            }
        }
    }
}