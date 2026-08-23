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
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.postEvent

private data class PaddingItem(
    val label: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit
)

class PaddingConfigDialog : ComposeDialogFragment() {

    override val widthFraction: Float = 0.91f
    override val maxWidthDp: Int? = 400

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
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
                ) {
                    PaddingConfigContent(style = style)
                }
            }
        }
    }

    @Composable
    private fun PaddingConfigContent(style: AppDialogStyle) {
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
                        fontSize = 14.sp,
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
                            fontSize = 12.sp,
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.value.toString(),
                        color = style.accent,
                        fontSize = 12.sp,
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
