package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi

/**
 * **本书**高级标题拆分规则编辑弹框（NEW-56 接线）。
 *
 * 缺陷背景：`AdvancedTitleConfig.setBookRule(book, rule)` 全仓**零调用**，而读侧
 * `bookRule(book)` / `effectiveRule(book)`（`split(title, book)` 与 Lottie 标题渲染都在活路径）
 * 一直在读它 ⇒ **单书规则只有读、没有写**：用户明明可以让某本书用与全局不同的拆分方式（书名/章节
 * 前缀与全局规则不适配时），却在任何界面都**没有入口**（属「半接线」用户可感缺口）。
 *
 * 口径：
 * - 只编辑 [AdvancedTitleConfig.SplitRule]（`mode`/`delimiter`/`regex`），**不含** Lottie JSON 与高度因子
 *  （那是主题包级、由 [AdvancedTitleManageActivity] 管理）。
 * - 规则文本留空时，**预览**按全局规则出图（让用户看得见"跟随全局"的效果）；点「保存」需填规则，
 *  清空请用「恢复跟随全局」（两个动作用途不重叠，避免"空串=跟随全局"的歧义）。
 * - 保存/清除后 `postEvent(EventBus.UP_CONFIG, [5, 8])` —— 与全局管理页保存同通道，读页标题即时重渲染。
 */
class AdvancedTitleBookRuleDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 宿主主题作用域前置（G-37 / 铁律 S1）：内容根必须包 `LegadoTheme`
                LegadoTheme {
                    BookRuleContent()
                }
            }
        }
    }

    @Composable
    private fun BookRuleContent() {
        val context = LocalContext.current
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val emptyText = stringResource(R.string.empty)
        val defaultSample = stringResource(R.string.advanced_title_sample_default)
        val book = ReadBook.book
        // 已存在的本书规则快照：决定「恢复跟随全局」动作是否出现，以及初值
        val existing = remember { AdvancedTitleConfig.bookRule(book) }
        var useRegex by rememberSaveable {
            mutableStateOf(existing?.mode == AdvancedTitleConfig.SPLIT_REGEX)
        }
        var ruleText by rememberSaveable {
            mutableStateOf(
                existing?.let {
                    if (it.mode == AdvancedTitleConfig.SPLIT_REGEX) it.regex else it.delimiter
                }.orEmpty()
            )
        }
        var sampleText by rememberSaveable { mutableStateOf(defaultSample) }

        fun buildRule() = AdvancedTitleConfig.SplitRule(
            mode = if (useRegex) {
                AdvancedTitleConfig.SPLIT_REGEX
            } else {
                AdvancedTitleConfig.SPLIT_DELIMITER
            },
            delimiter = if (useRegex) AdvancedTitleConfig.globalRule.delimiter else ruleText,
            regex = if (useRegex) ruleText else AdvancedTitleConfig.globalRule.regex
        )

        // 规则文本留空 ⇒ 预览按全局规则出图（等价于「跟随全局」的可视结果）
        val previewRule = remember(useRegex, ruleText) {
            if (ruleText.isBlank()) AdvancedTitleConfig.globalRule else buildRule()
        }
        val previewText = remember(previewRule, sampleText) {
            kotlin.runCatching {
                val parts = AdvancedTitleConfig.split(sampleText, previewRule)
                context.getString(
                    R.string.advanced_title_preview_template,
                    parts.s1.ifBlank { emptyText },
                    parts.s2.ifBlank { emptyText }
                )
            }.getOrElse {
                context.getString(R.string.advanced_title_rule_error, it.localizedMessage.orEmpty())
            }
        }

        fun applyRule(rule: AdvancedTitleConfig.SplitRule?) {
            AdvancedTitleConfig.setBookRule(book ?: return, rule)
            postEvent(EventBus.UP_CONFIG, arrayListOf(5, 8))
            dismissAllowingStateLoss()
        }

        fun confirm() {
            if (ruleText.isBlank()) {
                context.toastOnUi(
                    context.getString(R.string.advanced_title_rule_label) +
                        context.getString(R.string.cannot_empty)
                )
                return
            }
            applyRule(buildRule())
        }

        AppDialogFrame(
            title = stringResource(R.string.advanced_title_book_rule),
            scrollContent = true,
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppDialogSwitchRow(
                        text = stringResource(R.string.advanced_title_use_regex),
                        checked = useRegex,
                        onCheckedChange = { next ->
                            useRegex = next
                            ruleText = ""
                        }
                    )
                    AdvancedTitleTextField(
                        value = ruleText,
                        onValueChange = { ruleText = it },
                        label = stringResource(R.string.advanced_title_rule_label),
                        style = style
                    )
                    AdvancedTitleTextField(
                        value = sampleText,
                        onValueChange = { sampleText = it },
                        label = stringResource(R.string.preview),
                        style = style
                    )
                    Text(
                        text = previewText,
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.advanced_title_book_rule_hint),
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
            },
            actions = {
                if (existing != null) {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.advanced_title_book_rule_reset),
                        palette = palette,
                        onClick = { applyRule(null) },
                        cornerRadius = style.actionRadius
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.confirm),
                    palette = palette,
                    onClick = { confirm() },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }
}
