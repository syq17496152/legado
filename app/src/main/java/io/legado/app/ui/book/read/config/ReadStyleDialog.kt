package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReaderFontWeight
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
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
                ReadStyleContent()
            }
        }
    }

    @Composable
    private fun ReadStyleContent() {
        ReaderBottomSheetFrame(maxHeightFraction = maxSheetHeightFraction) { style ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextMetricSection(style = style)
                AnimAndToolsSection(style = style)
                StyleLibrarySection(style = style)
            }
        }
    }

    @Composable
    private fun AnimAndToolsSection(style: AppDialogStyle) {
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
                    onClick = { showDialogFragment<FontSelectDialog>() }
                )
                ReaderTextAction(
                    text = stringResource(R.string.text_indent),
                    style = style,
                    modifier = Modifier.weight(1f),
                    onClick = { showTextIndentDialog() }
                )
                ReaderTextAction(
                    text = stringResource(R.string.padding),
                    style = style,
                    modifier = Modifier.weight(1f),
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
                ReaderTextAction(
                    text = stringResource(R.string.information),
                    style = style,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
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
        onValueChange: (Int) -> Unit,
        onValueChangeFinished: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp),
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight(value)
                    )
                    Text(
                        text = value.toString(),
                        color = style.accent,
                        fontSize = 13.sp,
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
                    valueRange = ReaderFontWeight.MIN.toFloat()..ReaderFontWeight.MAX.toFloat()
                )
            }
        }
    }

    @Composable
    private fun TextMetricSection(style: AppDialogStyle) {
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
        onValueChange: (Int) -> Unit
    ) {
        Surface(
            modifier = modifier.heightIn(min = 58.dp),
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = valueText,
                        color = style.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                AppThemedStepperSlider(
                    value = value.coerceIn(range),
                    range = range,
                    onValueChange = { onValueChange(it.coerceIn(range)) },
                    palette = style.toMiuixPalette(),
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
                fontSize = 11.sp,
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
                fontSize = 11.sp,
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
