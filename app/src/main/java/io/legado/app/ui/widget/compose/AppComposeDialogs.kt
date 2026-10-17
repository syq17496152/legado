package io.legado.app.ui.widget.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.themeDividerColorOrDefault
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils
import io.legado.app.ui.theme.LegadoTheme

internal const val MAX_SAVEABLE_MULTI_CHOICE_ITEMS = 128
internal const val MAX_ACTION_LIST_ITEMS = 64

@Composable
internal fun DismissWhenCallbackMissing(
    missing: Boolean,
    dismiss: () -> Unit
) {
    LaunchedEffect(missing) {
        if (missing) {
            dismiss()
        }
    }
}

@Stable
data class AppDialogStyle(
    val accent: Color,
    val onAccent: Color,
    val surface: Color,
    val fieldSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val stroke: Color,
    val danger: Color,
    val panelRadius: androidx.compose.ui.unit.Dp,
    val actionRadius: androidx.compose.ui.unit.Dp,
    val bodyFontFamily: FontFamily,
    val titleFontFamily: FontFamily
)

@Composable
fun rememberAppDialogStyle(): AppDialogStyle {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val layoutAlpha = if (AppConfig.isEInkMode) {
        1f
    } else {
        AppConfig.dialogAlpha.coerceIn(0, 100) / 100f
    }
    val themeUiPalette = rememberThemeUiPalette()
    val customSurface = themeUiPalette.cardColor.takeIf { themeUiPalette.hasCustomCardColor }
    val customFieldSurface = themeUiPalette.mutedColor.takeIf { themeUiPalette.hasCustomMutedColor }
    val surfaceBase = customSurface ?: context.bottomBackground
    val fieldSurface = customFieldSurface ?: ColorUtils.blendColors(
        surfaceBase,
        accent,
        if (night) 0.10f else 0.05f
    )
    val fieldAlpha = (layoutAlpha + if (night) 0.10f else 0.08f).coerceIn(0f, 1f)
    val stroke = UiCorner.panelBorderColor(context)
        ?: if (customSurface != null || customFieldSurface != null) {
            context.themeDividerColorOrDefault()
        } else {
            UiCorner.effectStrokeColor(surfaceBase)
        }
    // 文字色按对话框实际背景明暗推导，而非全局 night 标志，避免深底深字/浅底白字
    return AppDialogStyle(
        accent = Color(accent),
        // onAccent 单源（AD-15）：统一走 AppUiTokens.onAccent → ThemeSpec.contrastOn，禁止在此重复推导
        onAccent = AppUiTokens.onAccent(Color(accent)),
        surface = Color(ColorUtils.withAlpha(surfaceBase, layoutAlpha)),
        fieldSurface = Color(ColorUtils.withAlpha(fieldSurface, fieldAlpha)),
        primaryText = Color(context.primaryTextColor),
        secondaryText = Color(context.secondaryTextColor),
        stroke = Color(stroke),
        // 语义色单源（AD-14）：danger 真值见 AppSemanticColors，禁止在此写具体色值
        danger = AppSemanticColors.Danger,
        panelRadius = context.composePanelRadius(),
        actionRadius = context.composeActionRadius(),
        bodyFontFamily = FontFamily(context.uiTypeface()),
        titleFontFamily = FontFamily(context.titleTypeface())
    )
}

/**
 * 弹层统一框架（NEW-73：**主题作用域由本框架单点注入**）。
 *
 * 背景：全仓 40 个弹窗宿主以 `setContent { AppDialogFrame(…) }` 为内容根，而框架此前只提供
 * `rememberAppDialogStyle()` 直色（框架自身文字/卡片色）——其内部使用的 M3 组件
 * （`TextField` / `Switch` / `DropdownMenu` / `Icon` 等）读 `MaterialTheme.colorScheme`，
 * 在夜间/主题包/自定义主题色下回落 **M3 默认亮色基线**（浅色输入框、白底菜单）。
 * 现由框架统一补 `LegadoTheme`：调用点无需逐个包裹，该类缺陷结构上不再出现。
 * 已显式包裹的宿主形成幂等嵌套（同值 MaterialTheme 重复下发无副作用）。
 * 门禁 G-37 的「框架提供作用域」豁免**自校验本文件**（此处移除 `LegadoTheme` ⇒ 40 处立即重新按违规计）。
 */
@Composable
fun AppDialogFrame(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    scrollContent: Boolean = true,
    messageInContent: Boolean = false,
    titleTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    LegadoTheme {
        AppDialogFrameContent(
            title = title,
            modifier = modifier,
            message = message,
            scrollContent = scrollContent,
            messageInContent = messageInContent,
            titleTrailing = titleTrailing,
            content = content,
            actions = actions
        )
    }
}

/**
 * 弹层框架的**原实现体**（自 NEW-73 起由 [AppDialogFrame] 在 `LegadoTheme` 内调用）。
 *
 * 拆分而非在原体内加重排缩进：原体约 85 行，逐行重排的改动面积与风险远高于「保留原体 + 外层包一层」。
 * 行为与拆分前逐字一致（参数逐项同名透传）。
 */
@Composable
private fun AppDialogFrameContent(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    scrollContent: Boolean = true,
    messageInContent: Boolean = false,
    titleTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val style = rememberAppDialogStyle()
    val frameShape = RoundedCornerShape(style.panelRadius)
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .shadow(14.dp, frameShape, clip = false)
                .border(1.dp, style.stroke, frameShape),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                // 标题行（ui-theme-governance-polish P2）：titleTrailing 槽用于三点菜单收纳低频按钮，
                // 默认 null 时与旧结构等价（Text 独占）；weight(1f)+maxLines(2)+Ellipsis 保留防长标题挤出
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = style.primaryText,
                        fontSize = MaterialTheme.typography.titleLarge.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = style.titleFontFamily,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    titleTrailing?.invoke()
                }
                if (!message.isNullOrBlank() && !messageInContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AppDialogMessageText(message = message, style = style)
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 内容区**自适应**：保留 520dp 视觉上限，同时允许在**矮窗口**下收缩。
                // 铁证 2026-10-13（横屏 1600x900 / density 300 ⇒ 可视高仅 480dp）：固定 `heightIn(max=520dp)`
                // 会让「文件选择」弹框内容（路径行 + 400dp 列表）把动作区（新建文件夹 / OK）**挤出可视区且不可达**
                // ⇒ archive ZIP 主题包导入在真机上无法完成。`weight(fill=false)` 在有界窗口下把剩余高度让给内容，
                // 内容短时行为与旧实现完全一致（wrap 内容），仅在窗口不足时才收缩。
                val contentModifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 520.dp)
                val shouldScrollContent = scrollContent || messageInContent
                if (shouldScrollContent) {
                    Column(
                        modifier = contentModifier.verticalScroll(rememberScrollState())
                    ) {
                        AppDialogContent(
                            message = message,
                            messageInContent = messageInContent,
                            style = style,
                            content = content
                        )
                    }
                } else {
                    Column(modifier = contentModifier) {
                        AppDialogContent(
                            message = message,
                            messageInContent = messageInContent,
                            style = style,
                            content = content
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

@Composable
private fun AppDialogContent(
    message: String?,
    messageInContent: Boolean,
    style: AppDialogStyle,
    content: @Composable () -> Unit
) {
    if (!message.isNullOrBlank() && messageInContent) {
        AppDialogMessageText(message = message, style = style)
    }
    content()
}

@Composable
private fun AppDialogMessageText(
    message: String,
    style: AppDialogStyle
) {
    SelectionContainer {
        Text(
            text = message,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun AppDialogFieldLabel(label: String, style: AppDialogStyle) {
    label.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 弹框输入框配色（String 版与 TextFieldValue 版共用 ⇒ 样式单源，避免两处漂移） */
@Composable
private fun AppDialogFieldColors(style: AppDialogStyle) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = style.primaryText,
    unfocusedTextColor = style.primaryText,
    disabledTextColor = style.secondaryText,
    focusedContainerColor = style.fieldSurface,
    unfocusedContainerColor = style.fieldSurface,
    disabledContainerColor = style.fieldSurface.copy(alpha = 0.58f),
    cursorColor = style.accent,
    focusedBorderColor = style.accent.copy(alpha = 0.55f),
    unfocusedBorderColor = style.stroke,
    disabledBorderColor = style.stroke.copy(alpha = 0.38f),
    focusedPlaceholderColor = style.secondaryText,
    unfocusedPlaceholderColor = style.secondaryText
)

@Composable
internal fun AppDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    minLines: Int = 1,
    maxLines: Int = 1,
    singleLine: Boolean = false,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppDialogFieldLabel(label = label, style = style)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            singleLine = singleLine,
            readOnly = readOnly,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(style.actionRadius),
            colors = AppDialogFieldColors(style),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

/**
 * `TextFieldValue` 版输入框（R12 模板编辑器用）。
 *
 * 与 String 版差别只有一处：**保留选区**（光标位置）。模板编辑器要把「点选占位符」
 * 插入到光标处，String 版无法表达光标 ⇒ 需要该版本；样式与 String 版共用
 * `AppDialogFieldColors`，不复制配色。
 */
@Composable
internal fun AppDialogValueTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    minLines: Int = 1,
    maxLines: Int = 4,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppDialogFieldLabel(label = label, style = style)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = minLines,
            maxLines = maxLines,
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(style.actionRadius),
            colors = AppDialogFieldColors(style),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}
