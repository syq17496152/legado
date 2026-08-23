package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

object ReaderSheetDefaults {
    val MaxHeightFraction = 0.72f
    val ContentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    val SectionPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
    val SectionGap = 8.dp
    val ActionHeight = 38.dp
}

@Composable
fun rememberReaderMenuDialogStyle(): AppDialogStyle {
    val context = LocalContext.current
    val bgColor = if (AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0) {
        runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    return rememberReaderMenuDialogStyle(bgColor)
}

@Composable
fun rememberReaderMenuDialogStyle(bgColor: Int): AppDialogStyle {
    val context = LocalContext.current
    val base = rememberAppDialogStyle()
    val palette = ReaderSheetStyle.resolve(context, bgColor)
    return base.copy(
        surface = Color(palette.surface),
        fieldSurface = Color(palette.panel),
        primaryText = Color(palette.textColor),
        secondaryText = Color(palette.secondaryTextColor),
        accent = Color(palette.accentColor),
        stroke = Color(palette.stroke)
    )
}

abstract class ReaderBottomSheetComposeDialogFragment : ComposeDialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    protected open val maxSheetHeightFraction: Float = ReaderSheetDefaults.MaxHeightFraction

    private var bottomDialogRegistered = false

    override fun onStart() {
        super.onStart()
        registerBottomDialog()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
            attr.gravity = Gravity.BOTTOM
            attr.windowAnimations = if (AppConfig.isEInkMode) 0 else R.style.AnimDialogBottom
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        unregisterBottomDialog()
        super.onDismiss(dialog)
    }

    private fun registerBottomDialog() {
        if (bottomDialogRegistered) return
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog++
            bottomDialogRegistered = true
        }
    }

    private fun unregisterBottomDialog() {
        if (!bottomDialogRegistered) return
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog--
        }
        bottomDialogRegistered = false
    }
}

@Composable
fun ReaderBottomSheetFrame(
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = ReaderSheetDefaults.MaxHeightFraction,
    contentPadding: PaddingValues = ReaderSheetDefaults.ContentPadding,
    content: @Composable (AppDialogStyle) -> Unit
) {
    val style = rememberReaderMenuDialogStyle()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * maxHeightFraction)
        .toInt()
        .coerceAtLeast(280)
        .dp
    val shape = RoundedCornerShape(topStart = style.panelRadius, topEnd = style.panelRadius)
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding(),
            shape = shape,
            color = style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content(style)
            }
        }
    }
}

@Composable
fun ReaderSheetHeader(
    title: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        trailing?.let {
            Spacer(modifier = Modifier.width(10.dp))
            it()
        }
    }
}

@Composable
fun ReaderSectionCard(
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = ReaderSheetDefaults.SectionPadding,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(ReaderSheetDefaults.SectionGap)
        ) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = style.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content()
        }
    }
}

@Composable
fun ReaderTextAction(
    text: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bgColor = if (selected) style.accent.copy(alpha = 0.18f) else style.fieldSurface
    val textColor = if (enabled) style.primaryText else style.secondaryText.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ReaderSheetDefaults.ActionHeight)
            .clip(RoundedCornerShape(style.actionRadius))
            .background(bgColor)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ReaderSwitchRow(
    title: String,
    checked: Boolean,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) style.primaryText else style.secondaryText.copy(alpha = 0.55f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        LegadoMiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            palette = style.toMiuixPalette(),
            enabled = enabled
        )
    }
}

@Composable
fun ReaderSegmentedOptions(
    options: List<ReaderOption>,
    selectedValue: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    pillStyle: Boolean = false,
    onSelected: (String) -> Unit
) {
    val cornerRadius = style.actionRadius
    val hPad = if (pillStyle) 12.dp else if (scrollable) 12.dp else 8.dp
    val vPad = if (pillStyle) 6.dp else 8.dp
    if (scrollable) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            options.forEach { option ->
                val selected = option.value == selectedValue
                val bgColor = if (selected) style.accent else style.fieldSurface
                val textColor = if (selected) Color.White else style.primaryText
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(bgColor)
                        .clickable { onSelected(option.value) }
                        .padding(horizontal = hPad, vertical = vPad),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            options.forEach { option ->
                val selected = option.value == selectedValue
                val bgColor = if (selected) style.accent else style.fieldSurface
                val textColor = if (selected) Color.White else style.primaryText
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 30.dp)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(bgColor)
                        .clickable { onSelected(option.value) }
                        .padding(horizontal = hPad, vertical = vPad),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

data class ReaderOption(
    val value: String,
    val label: String
)
