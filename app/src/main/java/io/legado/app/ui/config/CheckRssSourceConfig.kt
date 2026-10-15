package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.model.CheckRssSource
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.putPrefString
import io.legado.app.utils.toastOnUi

/**
 * 订阅源校验设置（NEW-33，书源侧 [CheckSourceConfig] 的同构入口）。
 *
 * 缺陷背景：`CheckRssSource` 的 8 个配置项**只有默认值**——`summary` 与
 * `check_rss_source_config_summary` 等字符串虽已就位却全仓零消费者（半成品漏接线），
 * 用户在设置页无任何入口调整订阅源校验的超时/检查项/去重 ⇒ 订阅源巡检只能跑默认档。
 *
 * 依赖口径（与 [CheckSourceConfig] 不同，**刻意不做灰显联动**）：订阅源侧每个维度都由源自身能力
 * 自闸（`checkArticles` 需 `ruleArticles` 非空、`checkSearch` 需 `searchUrl`、`checkSort` 需 `sortUrl`、
 * `checkContent` 需 `ruleContent`，见 `CheckRssSourceService.doCheckRssSource`）⇒ 维度间无先后依赖，
 * 勾选即为"允许校验该维度"，未勾选不会导致其他维度失效（凭空造依赖会改变既有判据）。
 */
class CheckRssSourceConfig : ComposeDialogFragment() {

    //允许的最小超时时间，秒
    private val minTimeout = 0L

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // G-37/K1：弹窗内容必须在应用主题作用域内（与书源校验设置弹窗同口径）
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    CompositionLocalProvider(
                        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                    ) {
                        CheckRssSourceConfigContent(style = style)
                    }
                }
            }
        }
    }

    @Composable
    private fun CheckRssSourceConfigContent(style: AppDialogStyle) {
        var timeoutText by rememberSaveable { mutableStateOf((CheckRssSource.timeout / 1000).toString()) }
        var checkDomain by rememberSaveable { mutableStateOf(CheckRssSource.checkDomain) }
        // 域名校验方式：0=Socket快速检测, 1=AnalyzeUrl真实请求(默认)
        var domainCheckMode by rememberSaveable { mutableStateOf(CheckRssSource.domainCheckMode) }
        var checkArticles by rememberSaveable { mutableStateOf(CheckRssSource.checkArticles) }
        var checkSearch by rememberSaveable { mutableStateOf(CheckRssSource.checkSearch) }
        var checkSort by rememberSaveable { mutableStateOf(CheckRssSource.checkSort) }
        var checkContent by rememberSaveable { mutableStateOf(CheckRssSource.checkContent) }
        var enableDedup by rememberSaveable { mutableStateOf(CheckRssSource.enableDedup) }
        val palette = style.toMiuixPalette()

        AppDialogFrame(
            title = stringResource(R.string.check_rss_source_config),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CheckConfigNumberField(
                        value = timeoutText,
                        onValueChange = { timeoutText = it.filterAsciiDigits() },
                        label = stringResource(R.string.check_rss_source_timeout),
                        style = style
                    )
                    Text(
                        text = stringResource(R.string.check_source_item),
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.domain),
                        selected = checkDomain,
                        style = style,
                        onClick = { checkDomain = !checkDomain }
                    )
                    if (checkDomain) {
                        CheckConfigDomainModeRow(
                            text = stringResource(R.string.domain_check_socket),
                            selected = domainCheckMode == 0,
                            style = style,
                            onClick = { domainCheckMode = 0 }
                        )
                        CheckConfigDomainModeRow(
                            text = stringResource(R.string.domain_check_analyze_url),
                            selected = domainCheckMode == 1,
                            style = style,
                            onClick = { domainCheckMode = 1 }
                        )
                    }
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.check_rss_articles),
                        selected = checkArticles,
                        style = style,
                        onClick = { checkArticles = !checkArticles }
                    )
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.search),
                        selected = checkSearch,
                        style = style,
                        onClick = { checkSearch = !checkSearch }
                    )
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.check_rss_sort),
                        selected = checkSort,
                        style = style,
                        onClick = { checkSort = !checkSort }
                    )
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.main_body),
                        selected = checkContent,
                        style = style,
                        onClick = { checkContent = !checkContent }
                    )
                    CheckConfigChoiceRow(
                        text = stringResource(R.string.dedup),
                        selected = enableDedup,
                        style = style,
                        onClick = { enableDedup = !enableDedup }
                    )
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    primary = true,
                    onClick = {
                        saveConfig(
                            timeoutText = timeoutText,
                            checkDomain = checkDomain,
                            domainCheckMode = domainCheckMode,
                            checkArticles = checkArticles,
                            checkSearch = checkSearch,
                            checkSort = checkSort,
                            checkContent = checkContent,
                            enableDedup = enableDedup
                        )
                    },
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    private fun saveConfig(
        timeoutText: String,
        checkDomain: Boolean,
        domainCheckMode: Int,
        checkArticles: Boolean,
        checkSearch: Boolean,
        checkSort: Boolean,
        checkContent: Boolean,
        enableDedup: Boolean
    ) {
        val timeoutSeconds = timeoutText.toLongOrNull()
        when {
            timeoutText.isBlank() || timeoutSeconds == null -> {
                toastOnUi("${getString(R.string.timeout)}${getString(R.string.cannot_empty)}")
                return
            }
            timeoutSeconds <= minTimeout -> {
                toastOnUi(
                    "${getString(R.string.timeout)}${getString(R.string.less_than)}$minTimeout${
                        getString(R.string.seconds)
                    }"
                )
                return
            }
            else -> CheckRssSource.applyConfig(
                timeoutMs = timeoutSeconds * 1000,
                checkDomain = checkDomain,
                domainCheckMode = domainCheckMode,
                checkArticles = checkArticles,
                checkSearch = checkSearch,
                checkSort = checkSort,
                checkContent = checkContent,
                enableDedup = enableDedup
            )
        }
        requireContext().putPrefString(PreferKey.checkRssSource, CheckRssSource.summary)
        dismissAllowingStateLoss()
    }
}
