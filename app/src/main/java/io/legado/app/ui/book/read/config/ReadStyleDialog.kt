package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.releaseComposeImage
import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMarkdown
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReaderFontWeight
import io.legado.app.help.config.ReaderTemplateManager
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.image.CircleImageView
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

/**
 * 「被模板接管 ⇒ 置灰」的禁用不透明度（4.8d 优先级规则的可视化）。
 *
 * 只做视觉弱化（不引入新色值/新组件族成员），与既有 `ReaderTextAction` 的
 * `enabled=false`（文字降为 secondaryText）同一语义：**让用户一眼看出该项此刻不生效**。
 */
private const val DisabledAlpha = 0.45f

class ReadStyleDialog : ReaderBottomSheetComposeDialogFragment(),
    FontSelectDialog.CallBack {

    override val maxSheetHeightFraction: Float = 0.70f

    private val callBack get() = activity as? ReadBookActivity

    override fun onDismiss(dialog: DialogInterface) {
        ReadBookConfig.save()
        super.onDismiss(dialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // G-37：宿主入口**顶层**必须有主题作用域——否则内容子树在主题包/夜间下
                // 回落 M3 默认亮色基线（黑字黑图标）。`ReaderBottomSheetFrame` 只给弹层观感，
                // 不提供色板作用域。
                LegadoTheme {
                    ReadStyleContent()
                }
            }
        }
    }

    @Composable
    private fun ReadStyleContent() {
        // 模板是否**接管**当前正文排版（4.8d 优先级规则）：
        // 模板启用 且 当前内容是模板作用域内的形态（在线正文 / 本地 txt / 本地 md）。
        // 出版 EPUB / 漫画 / 图片 / 视频 / 音频 / PDF **不适用**模板 ⇒ 不得置灰原生排版（否则用户无从排版）
        val typographyLocked = remember { templateTakesOverTypography() }
        ReaderBottomSheetFrame(maxHeightFraction = maxSheetHeightFraction) { style ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ReaderTemplateManager.templatesEnabled()) {
                    TemplateDecorationSection(style = style, typographyLocked = typographyLocked)
                }
                TextMetricSection(style = style, typographyLocked = typographyLocked)
                AnimAndToolsSection(style = style, typographyLocked = typographyLocked)
                StyleLibrarySection(style = style)
            }
        }
    }

    /**
     * 页面模板区（4.8d / AD-33 / TPL-17②③）：装饰强度 + 专注模式 + **与原生排版的优先级说明**。
     *
     * 只在模板总开关开启时出现（关闭 ⇒ 该项完全不存在，无残留，与 4.9 关闭语义一致）。
     * 内容不在模板作用域时**只显示说明**、不给出无意义的开关（N1：入口能在但点了没反应）。
     */
    @Composable
    private fun TemplateDecorationSection(style: AppDialogStyle, typographyLocked: Boolean) {
        var level by rememberSaveable { mutableIntStateOf(ReaderTemplateManager.decorationLevel()) }
        var focus by rememberSaveable { mutableStateOf(ReaderTemplateManager.focusMode()) }
        val reduceMotion = remember { systemReduceMotion() }
        ReaderSectionCard(style = style, title = null) {
            Text(
                text = "页面模板",
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (typographyLocked) {
                    "模板已接管正文排版：字号 / 字体 / 行距 / 字重 / 边距由模板决定，下列对应项已置灰；关闭页面模板即恢复。"
                } else {
                    "该格式不支持页面模板（仅作用于在线正文 / 本地 txt / 本地 md），装饰设置在此内容上不生效。"
                },
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!typographyLocked) return@ReaderSectionCard
            ReaderSegmentedOptions(
                options = decorationOptions(),
                selectedValue = level.toString(),
                style = style,
                scrollable = true,
                pillStyle = true
            ) { value ->
                val next = value.toIntOrNull() ?: return@ReaderSegmentedOptions
                if (next != level) {
                    level = next
                    ReaderTemplateManager.setDecorationLevel(next)
                    reloadAfterTemplatePreferenceChanged()
                }
            }
            ReaderSwitchRow(
                title = "专注模式",
                checked = focus,
                style = style,
                summary = "隐藏页面装饰，只留正文"
            ) { checked ->
                focus = checked
                ReaderTemplateManager.setFocusMode(checked)
                reloadAfterTemplatePreferenceChanged()
            }
            // 说明只在不打扰时为空（reduce-motion 提示等）；开着专注意味着用户已知道装饰被隐藏
            ReaderTemplateManager.decorationNotice(reduceMotion).takeIf { it.isNotBlank() }?.let { notice ->
                Text(
                    text = notice,
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    private fun decorationOptions(): List<ReaderOption> = listOf(
        ReaderOption("0", "无"),
        ReaderOption("1", "轻"),
        ReaderOption("2", "中"),
        ReaderOption("3", "强")
    )

    /** 装饰偏好变更后重载当前章：沙箱里的档位是 init 时下发的，改后必须重渲染才能生效。 */
    private fun reloadAfterTemplatePreferenceChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    /** 模板是否接管当前正文排版（作用域判定与 `ReaderTemplateAvailabilityPolicy.TEXT_LIKE` 同口径）。 */
    private fun templateTakesOverTypography(): Boolean {
        if (!ReaderTemplateManager.templatesEnabled()) return false
        val book = ReadBook.book ?: return false
        return book.isOnLineTxt || book.isLocalTxt || book.isMarkdown
    }

    /** 系统"减少动效"（与 ReadBookActivity 同一判据；取不到按未开启处理）。 */
    private fun systemReduceMotion(): Boolean {
        val context = context ?: return false
        return runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    @Composable
    private fun AnimAndToolsSection(style: AppDialogStyle, typographyLocked: Boolean) {
        var selectedAnim by rememberSaveable { mutableIntStateOf(ReadBook.pageAnim()) }
        var shareLayout by rememberSaveable { mutableIntStateOf(if (ReadBookConfig.shareLayout) 1 else 0) }
        var textWeight by rememberSaveable { mutableIntStateOf(ReadBookConfig.textWeight) }
        var chineseMode by rememberSaveable { mutableIntStateOf(AppConfig.chineseConverterType) }
        val chineseLabels = stringArrayResource(R.array.chinese_mode)
        ReaderSectionCard(style = style, title = null) {
            ReaderSegmentedOptions(
                options = pageAnimOptions(),
                selectedValue = selectedAnim.toString(),
                style = style,
                scrollable = true,
                pillStyle = true
            ) { value ->
                val anim = value.toIntOrNull() ?: return@ReaderSegmentedOptions
                if (selectedAnim != anim) {
                    ReadBook.book?.setPageAnim(-1)
                    ReadBookConfig.pageAnim = anim
                    selectedAnim = anim
                    callBack?.upPageAnim()
                    ReadBook.loadContent(false)
                }
            }
            FontWeightSlider(
                value = textWeight,
                style = style,
                // 模板接管排版时字重由模板决定 ⇒ 置灰（否则"调了没反应"）
                enabled = !typographyLocked,
                onValueChange = { value ->
                    textWeight = value
                    ReadBookConfig.textWeight = value
                },
                onValueChangeFinished = {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 6))
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ReaderTextAction(
                    text = stringResource(R.string.text_font),
                    style = style,
                    modifier = Modifier.weight(1f),
                    enabled = !typographyLocked,
                    onClick = { showDialogFragment<FontSelectDialog>() }
                )
                ReaderTextAction(
                    text = stringResource(R.string.text_indent),
                    style = style,
                    modifier = Modifier.weight(1f),
                    enabled = !typographyLocked,
                    onClick = { showTextIndentDialog() }
                )
                ReaderTextAction(
                    // R13（B3）：原「边距」「信息」两个入口合并为单一「版面设置」——
                    // 弹窗内两段切换（边距 / 页眉页脚），无需二次进入；配置项键完全不变。
                    text = stringResource(R.string.layout_config),
                    style = style,
                    modifier = Modifier.weight(1f),
                    // 版面（边距/页眉页脚）同属原生排版：模板接管时置灰
                    enabled = !typographyLocked,
                    onClick = {
                        dismissAllowingStateLoss()
                        callBack?.showPaddingConfig()
                    }
                )
                ReaderTextAction(
                    text = chineseLabels.getOrElse(chineseMode) { "" },
                    style = style,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        chineseMode = (chineseMode + 1) % chineseLabels.size
                        AppConfig.chineseConverterType = chineseMode
                        ChineseUtils.unLoad(*TransType.entries.toTypedArray())
                        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                    }
                )
            }
            ReaderSwitchRow(
                title = stringResource(R.string.share_layout),
                checked = shareLayout == 1,
                style = style
            ) { checked ->
                shareLayout = if (checked) 1 else 0
                ReadBookConfig.shareLayout = checked
                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            }
        }
    }

    @Composable
    private fun FontWeightSlider(
        value: Int,
        style: AppDialogStyle,
        enabled: Boolean = true,
        onValueChange: (Int) -> Unit,
        onValueChangeFinished: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .alpha(if (enabled) 1f else DisabledAlpha),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.text_font_weight),
                        modifier = Modifier.weight(1f),
                        color = style.primaryText,
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        fontWeight = FontWeight(value)
                    )
                    Text(
                        text = value.toString(),
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                LegadoMiuixSlider(
                    value = value.toFloat(),
                    onValueChange = {
                        onValueChange(
                            it.roundToInt().coerceIn(ReaderFontWeight.MIN, ReaderFontWeight.MAX)
                        )
                    },
                    onValueChangeFinished = onValueChangeFinished,
                    palette = style.toMiuixPalette(),
                    enabled = enabled,
                    valueRange = ReaderFontWeight.MIN.toFloat()..ReaderFontWeight.MAX.toFloat()
                )
            }
        }
    }

    @Composable
    private fun TextMetricSection(style: AppDialogStyle, typographyLocked: Boolean) {
        var textSize by rememberSaveable { mutableIntStateOf(ReadBookConfig.textSize - 5) }
        var letterSpacing by rememberSaveable {
            mutableIntStateOf((ReadBookConfig.letterSpacing * 100).toInt() + 50)
        }
        var lineSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.lineSpacingExtra) }
        var paragraphSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.paragraphSpacing) }
        ReaderSectionCard(style = style, title = null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MetricSliderTile(
                        label = stringResource(R.string.text_size),
                        value = textSize,
                        valueText = (textSize + 5).toString(),
                        range = 0..45,
                        style = style,
                        modifier = Modifier.weight(1f),
                        enabled = !typographyLocked,
                        onValueChange = {
                            textSize = it
                            ReadBookConfig.textSize = it + 5
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    )
                    MetricSliderTile(
                        label = stringResource(R.string.text_letter_spacing),
                        value = letterSpacing,
                        valueText = ((letterSpacing - 50) / 100f).toString(),
                        range = 0..100,
                        style = style,
                        modifier = Modifier.weight(1f),
                        enabled = !typographyLocked,
                        onValueChange = {
                            letterSpacing = it
                            ReadBookConfig.letterSpacing = (it - 50) / 100f
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    MetricSliderTile(
                        label = stringResource(R.string.line_size),
                        value = lineSpacing,
                        valueText = ((lineSpacing - 10) / 10f).toString(),
                        range = 0..20,
                        style = style,
                        modifier = Modifier.weight(1f),
                        enabled = !typographyLocked,
                        onValueChange = {
                            lineSpacing = it
                            ReadBookConfig.lineSpacingExtra = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    )
                    MetricSliderTile(
                        label = stringResource(R.string.paragraph_size),
                        value = paragraphSpacing,
                        valueText = (paragraphSpacing / 10f).toString(),
                        range = 0..20,
                        style = style,
                        modifier = Modifier.weight(1f),
                        enabled = !typographyLocked,
                        onValueChange = {
                            paragraphSpacing = it
                            ReadBookConfig.paragraphSpacing = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MetricSliderTile(
        label: String,
        value: Int,
        valueText: String,
        range: IntRange,
        style: AppDialogStyle,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        onValueChange: (Int) -> Unit
    ) {
        Surface(
            modifier = modifier
                .heightIn(min = 58.dp)
                .alpha(if (enabled) 1f else DisabledAlpha),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        color = style.primaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = valueText,
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                AppThemedStepperSlider(
                    value = value.coerceIn(range),
                    range = range,
                    onValueChange = { onValueChange(it.coerceIn(range)) },
                    palette = style.toMiuixPalette(),
                    enabled = enabled,
                    trackHeight = 34.dp,
                    thumbSize = 26.dp,
                    endpointWidth = 30.dp
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StyleLibrarySection(style: AppDialogStyle) {
        var selectedIndex by rememberSaveable { mutableIntStateOf(ReadBookConfig.styleSelect) }
        var version by rememberSaveable { mutableIntStateOf(0) }
        val configs = remember(version) { ReadBookConfig.configList.toList() }
        ReaderSectionCard(style = style, title = null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                configs.forEachIndexed { index, config ->
                    StylePreviewItem(
                        config = config,
                        selected = selectedIndex == index,
                        style = style,
                        onClick = {
                            changeBgTextConfig(index)
                            selectedIndex = ReadBookConfig.styleSelect
                            version++
                        },
                        onLongClick = {
                            showBgTextConfig(index)
                        }
                    )
                }
                AddStyleItem(style = style) {
                    ReadBookConfig.configList.add(ReadBookConfig.Config())
                    showBgTextConfig(ReadBookConfig.configList.lastIndex)
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StylePreviewItem(
        config: ReadBookConfig.Config,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .width(62.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            ) {
                AndroidView(
                    factory = { viewContext ->
                        CircleImageView(viewContext).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            val padding = 6.dpToPx()
                            setPadding(padding, padding, padding, padding)
                        }
                    },
                update = { imageView ->
                    imageView.setText(config.name.ifBlank { context.getString(R.string.text) })
                    imageView.setTypeface(context.uiTypeface())
                    imageView.setTextColor(config.curTextColor())
                    imageView.setImageDrawable(config.curBgDrawable(100, 150))
                    imageView.borderColor = if (selected) context.accentColor else config.curTextColor()
                    imageView.setTextBold(selected)
                },
                onRelease = { it.releaseComposeImage() },
                modifier = Modifier.size(48.dp)
            )
            }
            Text(
                text = config.name.ifBlank { stringResource(R.string.text) },
                color = if (selected) style.accent else style.secondaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }

    @Composable
    private fun AddStyleItem(
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .width(62.dp)
                .heightIn(min = 68.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onClick),
                shape = CircleShape,
                color = style.fieldSurface,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.add),
                        tint = style.primaryText,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.add),
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }

    private fun showTextIndentDialog() {
        showComposeChoiceListDialog(
            title = getString(R.string.text_indent),
            labels = resources.getStringArray(R.array.indent).toList()
        ) { index ->
            ReadBookConfig.paragraphIndent = "　".repeat(index)
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun changeBgTextConfig(index: Int) {
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            ReadBookConfig.styleSelect = index
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        changeBgTextConfig(index)
        view?.post {
            dismissAllowingStateLoss()
            callBack?.showBgTextConfig()
        }
        return true
    }

    @Composable
    private fun pageAnimOptions(): List<ReaderOption> {
        return listOf(
            ReaderOption(PageAnim.coverPageAnim.toString(), stringResource(R.string.page_anim_cover)),
            ReaderOption(PageAnim.linkedCoverPageAnim.toString(), stringResource(R.string.page_anim_linked_cover)),
            ReaderOption(PageAnim.slidePageAnim.toString(), stringResource(R.string.page_anim_slide)),
            ReaderOption(PageAnim.simulationPageAnim.toString(), stringResource(R.string.page_anim_simulation)),
            ReaderOption(PageAnim.scrollPageAnim.toString(), stringResource(R.string.page_anim_scroll)),
            ReaderOption(PageAnim.noAnim.toString(), stringResource(R.string.page_anim_none))
        )
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }
}
