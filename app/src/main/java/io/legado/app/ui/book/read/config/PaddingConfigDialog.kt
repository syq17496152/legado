package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.config.AdvancedTitleManageActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import android.content.Intent
import androidx.compose.material3.MaterialTheme

private data class PaddingItem(
    val label: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit
)

/**
 * 版面设置（R13 合并：**边距 + 页眉页脚 同一弹窗两段切换**，无需二次进入）。
 *
 * - 第一段「边距」= 原 `PaddingConfigDialog` 的 Header/Body/Footer 三段（**配置项键完全不变**，旧配置零迁移）；
 * - 第二段「页眉页脚」= 复用 `TipConfigContent`（与 `TipConfigDialog` 同一实现，避免两处各写一份）；
 * - 两段共用 `ReadBookConfig` 的既有字段与 `EventBus.UP_CONFIG` 刷新链路 ⇒ 与原两条独立路径**等价**。
 */
class PaddingConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.91f
    override val maxWidthDp: Int? = 400

    /** 页眉页脚段配色的刷新令牌（与 `TipConfigDialog` 同源事件） */
    private var colorRefreshTick by mutableIntStateOf(0)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = window.attributes
            attr.dimAmount = 0f
            window.attributes = attr
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // R13：页眉页脚段的配色变更令牌（与页眉页脚内容体同源事件，保证观感一致）
        observeEvent<String>(EventBus.TIP_COLOR) {
            colorRefreshTick++
        }
        // 原 TipConfigDialog 的标题模式兜底校验（合并后由本弹窗承担，行为等价）
        if (ReadBookConfig.titleMode !in 0..AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
            ReadBookConfig.titleMode = 0
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    LayoutConfigContent(
                        style = style,
                        colorRefreshTick = colorRefreshTick,
                        onShowAdvancedTitleConfig = {
                            startActivity(
                                Intent(requireContext(), AdvancedTitleManageActivity::class.java)
                            )
                        },
                        onShowSelector = ::showActionSelector,
                        onShowTipColorPicker = {
                            ColorPickerDialog.newBuilder()
                                .setShowAlphaSlider(false)
                                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                .setDialogId(TIP_COLOR)
                                .show(requireActivity())
                        },
                        onShowTipDividerColorPicker = {
                            ColorPickerDialog.newBuilder()
                                .setShowAlphaSlider(false)
                                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                .setDialogId(TIP_DIVIDER_COLOR)
                                .show(requireActivity())
                        },
                        onColorChanged = { colorRefreshTick++ }
                    )
                }
            }
        }
    }

    private fun showActionSelector(
        title: String,
        labels: List<String>,
        onSelected: (Int) -> Unit
    ) {
        ComposeActionListDialog.create(
            title = title,
            labels = labels,
            negativeText = getString(R.string.cancel),
            onSelected = onSelected
        ).show(parentFragmentManager, "layoutConfigSelector")
    }

    /**
     * 版面设置内容体：**顶部分段切换 + 对应段落**。
     *
     * 只做「容器 + 切换」，两段内容分别复用既有实现（边距三段 / `TipConfigContent`），
     * 避免任何渲染逻辑复制（复制即口径分裂）。
     */
    @Composable
    private fun LayoutConfigContent(
        style: AppDialogStyle,
        colorRefreshTick: Int,
        onShowAdvancedTitleConfig: () -> Unit,
        onShowSelector: (String, List<String>, (Int) -> Unit) -> Unit,
        onShowTipColorPicker: () -> Unit,
        onShowTipDividerColorPicker: () -> Unit,
        onColorChanged: () -> Unit
    ) {
        var section by rememberSaveable { mutableIntStateOf(0) }
        Column(modifier = Modifier.fillMaxWidth()) {
            LayoutSectionSwitch(
                section = section,
                style = style,
                onSectionChange = { section = it }
            )
            when (section) {
                0 -> PaddingSectionCard(style = style)
                else -> TipConfigContent(
                    style = style,
                    colorRefreshTick = colorRefreshTick,
                    onShowAdvancedTitleConfig = onShowAdvancedTitleConfig,
                    onShowSelector = onShowSelector,
                    onShowTipColorPicker = onShowTipColorPicker,
                    onShowTipDividerColorPicker = onShowTipDividerColorPicker,
                    onColorChanged = onColorChanged
                )
            }
        }
    }

    /** 两段切换条（复用全站 chip 选择行组件，与页眉页脚段的标题模式选择同款） */
    @Composable
    private fun LayoutSectionSwitch(
        section: Int,
        style: AppDialogStyle,
        onSectionChange: (Int) -> Unit
    ) {
        val palette = style.toMiuixPalette()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                stringResource(R.string.layout_config_padding),
                stringResource(R.string.layout_config_tip)
            ).forEachIndexed { index, label ->
                LegadoMiuixChoiceRow(
                    text = label,
                    selected = section == index,
                    palette = palette,
                    onClick = { onSectionChange(index) },
                    modifier = Modifier.weight(1f),
                    minHeight = 32.dp,
                    compact = true,
                    showSelectedMark = false
                )
            }
        }
    }

    /** 第一段「边距」（原实现原样保留：Header / Body / Footer 三段 + 同键同刷新链路） */
    @Composable
    private fun PaddingSectionCard(style: AppDialogStyle) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeaderSection(style = style)
                BodySection(style = style)
                FooterSection(style = style)
            }
        }
    }

    @Composable
    private fun HeaderSection(style: AppDialogStyle) {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showHeaderLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.headerPaddingRight) }

        PaddingSection(
            title = stringResource(R.string.header),
            style = style,
            showLine = showLine,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showHeaderLine = it
                postHeaderFooterChanged()
            }
        ) {
            PaddingSliderRows(
                style = style,
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..100,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.headerPaddingTop = it
                    postHeaderFooterChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.headerPaddingBottom = it
                    postHeaderFooterChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.headerPaddingLeft = it
                    postHeaderFooterChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.headerPaddingRight = it
                    postHeaderFooterChanged()
                }
            )
        }
    }

    @Composable
    private fun BodySection(style: AppDialogStyle) {
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.paddingRight) }

        PaddingSection(
            title = stringResource(R.string.main_body),
            style = style
        ) {
            PaddingSliderRows(
                style = style,
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..200,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.paddingTop = it
                    postBodyChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.paddingBottom = it
                    postBodyChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.paddingLeft = it
                    postBodyChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.paddingRight = it
                    postBodyChanged()
                }
            )
        }
    }

    @Composable
    private fun FooterSection(style: AppDialogStyle) {
        var showLine by rememberSaveable { mutableStateOf(ReadBookConfig.showFooterLine) }
        var top by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingTop) }
        var bottom by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingBottom) }
        var left by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingLeft) }
        var right by rememberSaveable { mutableIntStateOf(ReadBookConfig.footerPaddingRight) }

        PaddingSection(
            title = stringResource(R.string.footer),
            style = style,
            showLine = showLine,
            onShowLineChange = {
                showLine = it
                ReadBookConfig.showFooterLine = it
                postHeaderFooterChanged()
            }
        ) {
            PaddingSliderRows(
                style = style,
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                topRange = 0..100,
                bottomRange = 0..100,
                sideRange = 0..100,
                onTopChange = {
                    top = it
                    ReadBookConfig.footerPaddingTop = it
                    postHeaderFooterChanged()
                },
                onBottomChange = {
                    bottom = it
                    ReadBookConfig.footerPaddingBottom = it
                    postHeaderFooterChanged()
                },
                onLeftChange = {
                    left = it
                    ReadBookConfig.footerPaddingLeft = it
                    postHeaderFooterChanged()
                },
                onRightChange = {
                    right = it
                    ReadBookConfig.footerPaddingRight = it
                    postHeaderFooterChanged()
                }
            )
        }
    }

    @Composable
    private fun PaddingSection(
        title: String,
        style: AppDialogStyle,
        showLine: Boolean? = null,
        onShowLineChange: ((Boolean) -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        fontFamily = style.titleFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (showLine != null && onShowLineChange != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.showLine),
                            modifier = Modifier.widthIn(max = 72.dp),
                            color = style.secondaryText,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PaddingLineSwitch(
                            checked = showLine,
                            onCheckedChange = onShowLineChange,
                            style = style
                        )
                    }
                }
                content()
            }
        }
    }

    @Composable
    private fun PaddingLineSwitch(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        style: AppDialogStyle
    ) {
        val thumbSize = 24.dp
        val innerPadding = 2.dp
        Surface(
            modifier = Modifier
                .width(48.dp)
                .height(28.dp)
                .clickable { onCheckedChange(!checked) },
            shape = CircleShape,
            color = if (checked) style.accent.copy(alpha = 0.86f) else style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.CenterStart
            ) {
                val targetOffset = if (checked) {
                    maxWidth - thumbSize
                } else {
                    0.dp
                }
                val offset by animateDpAsState(
                    targetValue = targetOffset,
                    label = "paddingLineSwitchThumb"
                )
                Surface(
                    modifier = Modifier
                        .offset(x = offset)
                        .size(thumbSize),
                    shape = CircleShape,
                    color = style.surface,
                    contentColor = style.primaryText,
                    tonalElevation = 0.dp,
                    shadowElevation = 5.dp
                ) {}
            }
        }
    }

    @Composable
    private fun PaddingSliderRows(
        style: AppDialogStyle,
        top: Int,
        bottom: Int,
        left: Int,
        right: Int,
        topRange: IntRange,
        bottomRange: IntRange,
        sideRange: IntRange,
        onTopChange: (Int) -> Unit,
        onBottomChange: (Int) -> Unit,
        onLeftChange: (Int) -> Unit,
        onRightChange: (Int) -> Unit
    ) {
        val items = listOf(
            PaddingItem(stringResource(R.string.top), top, topRange) { if (it != top) onTopChange(it) },
            PaddingItem(stringResource(R.string.bottom), bottom, bottomRange) { if (it != bottom) onBottomChange(it) },
            PaddingItem(stringResource(R.string.left), left, sideRange) { if (it != left) onLeftChange(it) },
            PaddingItem(stringResource(R.string.right), right, sideRange) { if (it != right) onRightChange(it) }
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    rowItems.forEach { item ->
                        PaddingSliderTile(
                            item = item,
                            style = style,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PaddingSliderTile(
        item: PaddingItem,
        style: AppDialogStyle,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier.heightIn(min = 58.dp),
            shape = RoundedCornerShape(style.actionRadius),
            color = style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp
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
                        text = item.label,
                        modifier = Modifier.weight(1f),
                        color = style.primaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.value.toString(),
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                PaddingStepperSlider(
                    item = item,
                    style = style
                )
            }
        }
    }

    @Composable
    private fun PaddingStepperSlider(
        item: PaddingItem,
        style: AppDialogStyle
    ) {
        AppThemedStepperSlider(
            value = item.value,
            range = item.range,
            onValueChange = item.onValueChange,
            palette = style.toMiuixPalette(),
            trackHeight = 34.dp,
            thumbSize = 26.dp,
            endpointWidth = 30.dp
        )
    }

    private fun postBodyChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(10, 5))
    }

    private fun postHeaderFooterChanged() {
        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
    }
}
