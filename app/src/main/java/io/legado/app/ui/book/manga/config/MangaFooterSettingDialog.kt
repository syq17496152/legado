package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.ReaderInfoBarView
import io.legado.app.ui.widget.compose.AppDialogOptionGroup
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent

class MangaFooterSettingDialog : ComposeDialogFragment() {

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT

    private val config = GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig)
        .getOrNull()
        ?: MangaFooterConfig()
    private var latestConfig = config.copy()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    MangaFooterSettingContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun MangaFooterSettingContent(style: AppDialogStyle) {
        var hideChapterLabel by rememberSaveable { mutableStateOf(config.hideChapterLabel) }
        var hideChapter by rememberSaveable { mutableStateOf(config.hideChapter) }
        var hideChapterName by rememberSaveable { mutableStateOf(config.hideChapterName) }
        var hidePageNumberLabel by rememberSaveable { mutableStateOf(config.hidePageNumberLabel) }
        var hidePageNumber by rememberSaveable { mutableStateOf(config.hidePageNumber) }
        var hideProgressRatioLabel by rememberSaveable {
            mutableStateOf(config.hideProgressRatioLabel)
        }
        var hideProgressRatio by rememberSaveable { mutableStateOf(config.hideProgressRatio) }
        var hideFooter by rememberSaveable { mutableStateOf(config.hideFooter) }
        var footerOrientation by rememberSaveable { mutableStateOf(config.footerOrientation) }

        fun currentConfig(): MangaFooterConfig {
            return MangaFooterConfig(
                hideChapterLabel = hideChapterLabel,
                hideChapter = hideChapter,
                hidePageNumberLabel = hidePageNumberLabel,
                hidePageNumber = hidePageNumber,
                hideProgressRatioLabel = hideProgressRatioLabel,
                hideProgressRatio = hideProgressRatio,
                footerOrientation = footerOrientation,
                hideFooter = hideFooter,
                hideChapterName = hideChapterName
            )
        }

        fun updateConfig() {
            latestConfig = currentConfig()
            postEvent(EventBus.UP_MANGA_CONFIG, latestConfig)
        }

        SideEffect {
            latestConfig = currentConfig()
        }

        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.manga_footer_config),
                    color = style.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MangaFooterSwitchSection(
                        title = stringResource(R.string.manga_header_chapter),
                        style = style,
                        rows = listOf(
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_chapter_label),
                                checked = hideChapterLabel,
                                onChanged = { checked ->
                                    hideChapterLabel = checked
                                    updateConfig()
                                }
                            ),
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_chapter),
                                checked = hideChapter,
                                onChanged = { checked ->
                                    hideChapter = checked
                                    updateConfig()
                                }
                            ),
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_chapter_name),
                                checked = hideChapterName,
                                onChanged = { checked ->
                                    hideChapterName = checked
                                    updateConfig()
                                }
                            )
                        )
                    )
                    MangaFooterSwitchSection(
                        title = stringResource(R.string.manga_header_page),
                        style = style,
                        rows = listOf(
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_page_label),
                                checked = hidePageNumberLabel,
                                onChanged = { checked ->
                                    hidePageNumberLabel = checked
                                    updateConfig()
                                }
                            ),
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_page_number),
                                checked = hidePageNumber,
                                onChanged = { checked ->
                                    hidePageNumber = checked
                                    updateConfig()
                                }
                            )
                        )
                    )
                    MangaFooterSwitchSection(
                        title = stringResource(R.string.manga_header_progress),
                        style = style,
                        rows = listOf(
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_progress_label),
                                checked = hideProgressRatioLabel,
                                onChanged = { checked ->
                                    hideProgressRatioLabel = checked
                                    updateConfig()
                                }
                            ),
                            MangaFooterSwitchRow(
                                text = stringResource(R.string.manga_check_progress),
                                checked = hideProgressRatio,
                                onChanged = { checked ->
                                    hideProgressRatio = checked
                                    updateConfig()
                                }
                            )
                        )
                    )
                    AppDialogOptionGroup(
                        title = stringResource(R.string.manga_header_footer),
                        options = listOf(stringResource(R.string.show), stringResource(R.string.hide)),
                        selectedIndex = if (hideFooter) 1 else 0,
                        onSelected = { index ->
                            val newValue = index == 1
                            hideFooter = newValue
                            updateConfig()
                        }
                    )
                    AppDialogOptionGroup(
                        title = stringResource(R.string.manga_header_footer_alignment),
                        options = listOf(
                            stringResource(R.string.manga_radio_left),
                            stringResource(R.string.manga_radio_center)
                        ),
                        selectedIndex = if (footerOrientation == ReaderInfoBarView.ALIGN_CENTER) 1 else 0,
                        onSelected = { index ->
                            val newValue = if (index == 1) {
                                ReaderInfoBarView.ALIGN_CENTER
                            } else {
                                ReaderInfoBarView.ALIGN_LEFT
                            }
                            footerOrientation = newValue
                            updateConfig()
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MangaFooterSwitchSection(
        title: String,
        style: AppDialogStyle,
        rows: List<MangaFooterSwitchRow>
    ) {
        Text(
            text = title,
            color = style.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                AppDialogSwitchRow(
                    text = row.text,
                    checked = row.checked,
                    onCheckedChange = row.onChanged
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaFooterConfig = GSON.toJson(latestConfig)
    }

    private data class MangaFooterSwitchRow(
        val text: String,
        val checked: Boolean,
        val onChanged: (Boolean) -> Unit
    )
}
