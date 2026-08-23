package io.legado.app.ui.widget.compose

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
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
import kotlin.math.roundToInt

private const val MAX_SAVEABLE_MULTI_CHOICE_ITEMS = 128
private const val MAX_ACTION_LIST_ITEMS = 64

@Composable
private fun DismissWhenCallbackMissing(
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
        onAccent = if (ColorUtils.isColorLight(accent)) Color.Black else Color.White,
        surface = Color(ColorUtils.withAlpha(surfaceBase, layoutAlpha)),
        fieldSurface = Color(ColorUtils.withAlpha(fieldSurface, fieldAlpha)),
        primaryText = Color(context.primaryTextColor),
        secondaryText = Color(context.secondaryTextColor),
        stroke = Color(stroke),
        danger = Color(ContextCompat.getColor(context, R.color.md_red_500)),
        panelRadius = context.composePanelRadius(),
        actionRadius = context.composeActionRadius(),
        bodyFontFamily = FontFamily(context.uiTypeface()),
        titleFontFamily = FontFamily(context.titleTypeface())
    )
}

@Composable
fun AppDialogFrame(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    scrollContent: Boolean = true,
    messageInContent: Boolean = false,
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
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!message.isNullOrBlank() && !messageInContent) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AppDialogMessageText(message = message, style = style)
                }
                Spacer(modifier = Modifier.height(16.dp))
                val contentModifier = Modifier
                    .fillMaxWidth()
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
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun AppDialogTextField(
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
        label.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = style.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
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
            colors = OutlinedTextFieldDefaults.colors(
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
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

class ComposeTextInputDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var validateInput: ((String) -> Boolean)? = null
    private var onPositive: ((String) -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val readOnly = args.getBoolean(ARG_READ_ONLY)
                val hintText = args.getString(ARG_HINT).orEmpty()
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                val minLines = args.getInt(ARG_MIN_LINES)
                val maxLines = args.getInt(ARG_MAX_LINES)
                var text by rememberSaveable {
                    mutableStateOf(args.getString(ARG_INITIAL_TEXT).orEmpty())
                }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    content = {
                        AppDialogTextField(
                            value = text,
                            onValueChange = { if (!readOnly) text = it },
                            label = hintText,
                            minLines = minLines,
                            maxLines = maxLines,
                            readOnly = readOnly
                        )
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        val neutralLabel = neutralText
                        val neutralCallback = onNeutral
                        if (neutralLabel != null && neutralCallback != null) {
                            LegadoMiuixActionButton(
                                text = neutralLabel,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    neutralCallback.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        onPositive?.let { positiveCallback ->
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val current = text
                                    if (validateInput?.invoke(current) != false) {
                                        dismissAllowingStateLoss()
                                        positiveCallback.invoke(current)
                                    }
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            hint: String = "",
            initialValue: String = "",
            message: String? = null,
            readOnly: Boolean = false,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            minLines: Int = if (readOnly) 2 else 1,
            maxLines: Int = if (readOnly) 6 else 4,
            validateInput: ((String) -> Boolean)? = null,
            onPositive: (String) -> Unit,
            onNeutral: (() -> Unit)? = null
        ): ComposeTextInputDialog {
            return ComposeTextInputDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_HINT, hint)
                    putString(ARG_INITIAL_TEXT, initialValue)
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_READ_ONLY, readOnly)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                    putInt(ARG_MIN_LINES, minLines.coerceAtLeast(1))
                    putInt(ARG_MAX_LINES, maxLines.coerceAtLeast(minLines.coerceAtLeast(1)))
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
                this.onNeutral = onNeutral
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_HINT = "hint"
        private const val ARG_INITIAL_TEXT = "initialText"
        private const val ARG_MESSAGE = "message"
        private const val ARG_READ_ONLY = "readOnly"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
        private const val ARG_MIN_LINES = "minLines"
        private const val ARG_MAX_LINES = "maxLines"
    }
}

class ComposeSuggestionTextInputDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var validateInput: ((String) -> Boolean)? = null
    private var onPositive: ((String) -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null
    private var onSuggestionDeleted: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val hintText = args.getString(ARG_HINT).orEmpty()
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                val suggestions = remember {
                    mutableStateListOf<String>().apply {
                        addAll(args.getStringArrayList(ARG_SUGGESTIONS).orEmpty())
                    }
                }
                var text by rememberSaveable {
                    mutableStateOf(args.getString(ARG_INITIAL_TEXT).orEmpty())
                }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    content = {
                        AppDialogTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = hintText,
                            minLines = 1,
                            maxLines = 4
                        )
                        if (suggestions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(
                                    items = suggestions,
                                    key = { index, item -> "$index:$item" }
                                ) { index, suggestion ->
                                    SuggestionInputRow(
                                        text = suggestion,
                                        style = style,
                                        deletable = args.getBoolean(ARG_DELETABLE) &&
                                            onSuggestionDeleted != null,
                                        onClick = { text = suggestion },
                                        onDelete = {
                                            suggestions.removeAt(index)
                                            onSuggestionDeleted?.invoke(suggestion)
                                        }
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        val neutralLabel = neutralText
                        val neutralCallback = onNeutral
                        if (neutralLabel != null && neutralCallback != null) {
                            LegadoMiuixActionButton(
                                text = neutralLabel,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    neutralCallback.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        onPositive?.let { positiveCallback ->
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val current = text
                                    if (validateInput?.invoke(current) != false) {
                                        dismissAllowingStateLoss()
                                        positiveCallback.invoke(current)
                                    }
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            hint: String = "",
            initialValue: String = "",
            suggestions: List<String> = emptyList(),
            message: String? = null,
            deletable: Boolean = false,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            validateInput: ((String) -> Boolean)? = null,
            onPositive: (String) -> Unit,
            onNeutral: (() -> Unit)? = null,
            onSuggestionDeleted: ((String) -> Unit)? = null
        ): ComposeSuggestionTextInputDialog {
            return ComposeSuggestionTextInputDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_HINT, hint)
                    putString(ARG_INITIAL_TEXT, initialValue)
                    putStringArrayList(ARG_SUGGESTIONS, ArrayList(suggestions))
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_DELETABLE, deletable)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
                this.onNeutral = onNeutral
                this.onSuggestionDeleted = onSuggestionDeleted
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_HINT = "hint"
        private const val ARG_INITIAL_TEXT = "initialText"
        private const val ARG_SUGGESTIONS = "suggestions"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DELETABLE = "deletable"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
    }
}

@Composable
private fun SuggestionInputRow(
    text: String,
    style: AppDialogStyle,
    deletable: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = style.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (deletable) {
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete),
                    palette = palette,
                    onClick = onDelete,
                    danger = true,
                    cornerRadius = style.actionRadius,
                    minWidth = 58.dp,
                    minHeight = 32.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

class ComposeTextFormDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Wide

    private var validateInput: ((List<String>) -> Boolean)? = null
    private var onPositive: ((List<String>) -> Unit)? = null
    private var onPositiveWithChecks: ((List<String>, BooleanArray) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null && onPositiveWithChecks == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val labels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val checkboxLabels = remember {
                    args.getStringArrayList(ARG_CHECKBOX_LABELS)?.toList().orEmpty()
                }
                val initialValues = remember(labels) {
                    val rawValues = args.getStringArrayList(ARG_INITIAL_VALUES)?.toList().orEmpty()
                    List(labels.size) { index -> rawValues.getOrNull(index).orEmpty() }
                }
                val initialChecked = remember(checkboxLabels) {
                    val rawValues = args.getBooleanArray(ARG_CHECKBOX_VALUES) ?: booleanArrayOf()
                    List(checkboxLabels.size) { index -> rawValues.getOrNull(index) ?: false }
                }
                val passwordFlags = remember(labels) {
                    val rawFlags = args.getBooleanArray(ARG_PASSWORD_FLAGS) ?: booleanArrayOf()
                    BooleanArray(labels.size) { index -> rawFlags.getOrNull(index) ?: false }
                }
                var values by rememberSaveable(labels) { mutableStateOf(initialValues) }
                var checkedValues by rememberSaveable(checkboxLabels) { mutableStateOf(initialChecked) }
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    content = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            labels.forEachIndexed { index, label ->
                                val isPassword = passwordFlags.getOrNull(index) ?: false
                                AppDialogTextField(
                                    value = values.getOrNull(index).orEmpty(),
                                    onValueChange = { nextValue ->
                                        values = values.updateAt(index, nextValue, labels.size)
                                    },
                                    label = label,
                                    singleLine = true,
                                    visualTransformation = if (isPassword) {
                                        PasswordVisualTransformation()
                                    } else {
                                        VisualTransformation.None
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = if (isPassword) {
                                            KeyboardType.Password
                                        } else {
                                            KeyboardType.Text
                                        }
                                    )
                                )
                            }
                            checkboxLabels.forEachIndexed { index, label ->
                                val checked = checkedValues.getOrNull(index) ?: false
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            checkedValues = checkedValues.updateCheckedAt(index, !checked, checkboxLabels.size)
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { next ->
                                            checkedValues = checkedValues.updateCheckedAt(index, next, checkboxLabels.size)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = style.accent,
                                            uncheckedColor = style.secondaryText,
                                            checkmarkColor = style.surface
                                        )
                                    )
                                    Text(
                                        text = label,
                                        color = style.primaryText,
                                        fontFamily = style.bodyFontFamily,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        if (onPositive != null || onPositiveWithChecks != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val current = values
                                    val currentChecked = checkedValues.toBooleanArray()
                                    if (validateInput?.invoke(current) != false) {
                                        dismissAllowingStateLoss()
                                        onPositive?.invoke(current)
                                        onPositiveWithChecks?.invoke(current, currentChecked)
                                    }
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            initialValues: List<String>,
            passwordFields: Set<Int> = emptySet(),
            message: String? = null,
            positiveText: String,
            negativeText: String,
            validateInput: ((List<String>) -> Boolean)? = null,
            onPositive: (List<String>) -> Unit
        ): ComposeTextFormDialog {
            val safeLabels = labels.toList()
            return ComposeTextFormDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putStringArrayList(
                        ARG_INITIAL_VALUES,
                        ArrayList(List(safeLabels.size) { index ->
                            initialValues.getOrNull(index).orEmpty()
                        })
                    )
                    putBooleanArray(
                        ARG_PASSWORD_FLAGS,
                        BooleanArray(safeLabels.size) { index -> index in passwordFields }
                    )
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.validateInput = validateInput
                this.onPositive = onPositive
            }
        }

        fun createWithChecks(
            title: String,
            labels: List<String>,
            initialValues: List<String>,
            passwordFields: Set<Int> = emptySet(),
            checkboxLabels: List<String>,
            checkedIndices: Set<Int>,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            validateInput: ((List<String>) -> Boolean)? = null,
            onPositive: (List<String>, BooleanArray) -> Unit
        ): ComposeTextFormDialog {
            val safeLabels = labels.toList()
            val safeCheckboxLabels = checkboxLabels.toList()
            return ComposeTextFormDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putStringArrayList(
                        ARG_INITIAL_VALUES,
                        ArrayList(List(safeLabels.size) { index ->
                            initialValues.getOrNull(index).orEmpty()
                        })
                    )
                    putBooleanArray(
                        ARG_PASSWORD_FLAGS,
                        BooleanArray(safeLabels.size) { index -> index in passwordFields }
                    )
                    putStringArrayList(ARG_CHECKBOX_LABELS, ArrayList(safeCheckboxLabels))
                    putBooleanArray(
                        ARG_CHECKBOX_VALUES,
                        BooleanArray(safeCheckboxLabels.size) { index -> index in checkedIndices }
                    )
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.validateInput = validateInput
                this.onPositiveWithChecks = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_INITIAL_VALUES = "initialValues"
        private const val ARG_PASSWORD_FLAGS = "passwordFlags"
        private const val ARG_CHECKBOX_LABELS = "checkboxLabels"
        private const val ARG_CHECKBOX_VALUES = "checkboxValues"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}

private fun List<String>.updateAt(index: Int, value: String, size: Int): List<String> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i).orEmpty() }.apply {
        this[index] = value
    }
}

private fun List<Boolean>.updateCheckedAt(index: Int, value: Boolean, size: Int): List<Boolean> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i) ?: false }.apply {
        this[index] = value
    }
}

class ComposeNumberPickerDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    private var onPositive: ((Int) -> Unit)? = null
    private var onCustom: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val minValue = args.getInt(ARG_MIN_VALUE)
                val maxValue = args.getInt(ARG_MAX_VALUE)
                val safeMin = minOf(minValue, maxValue)
                val safeMax = maxOf(minValue, maxValue)
                val isDecimalMode = args.getBoolean(ARG_DECIMAL_MODE)
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val customText = args.getString(ARG_CUSTOM_TEXT)?.takeIf { it.isNotBlank() }
                var currentValue by rememberSaveable {
                    mutableIntStateOf(args.getInt(ARG_VALUE).coerceIn(safeMin, safeMax))
                }
                var inputText by rememberSaveable(currentValue, isDecimalMode) {
                    mutableStateOf(formatPickerValue(currentValue, isDecimalMode))
                }
                val usePercentSlider = !isDecimalMode &&
                    safeMax == 100 &&
                    safeMin in 0..100 &&
                    safeMin < safeMax

                fun inputValueOrCurrent(): Int {
                    return parsePickerValue(inputText, isDecimalMode)
                        ?.coerceIn(safeMin, safeMax)
                        ?: currentValue
                }

                fun commitValue(): Int {
                    val next = inputValueOrCurrent()
                    currentValue = next
                    inputText = formatPickerValue(next, isDecimalMode)
                    return next
                }

                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    scrollContent = true,
                    content = {
                        val palette = style.toMiuixPalette()
                        if (usePercentSlider) {
                            LegadoMiuixCard(
                                modifier = Modifier.fillMaxWidth(),
                                color = style.fieldSurface,
                                contentColor = style.primaryText,
                                cornerRadius = style.actionRadius,
                                insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = "${currentValue.coerceIn(safeMin, safeMax)}%",
                                    color = style.primaryText,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                AppThemedStepperSlider(
                                    value = currentValue.coerceIn(safeMin, safeMax),
                                    range = safeMin..safeMax,
                                    onValueChange = {
                                        currentValue = it.coerceIn(safeMin, safeMax)
                                        inputText = formatPickerValue(currentValue, isDecimalMode)
                                    },
                                    palette = palette
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                NumberPickerStepButton(
                                    text = "-",
                                    enabled = inputValueOrCurrent() > safeMin,
                                    palette = palette,
                                    onClick = {
                                        currentValue = (inputValueOrCurrent() - 1).coerceAtLeast(safeMin)
                                        inputText = formatPickerValue(currentValue, isDecimalMode)
                                    }
                                )
                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = when {
                                            safeMin < 0 -> KeyboardType.Text
                                            isDecimalMode -> KeyboardType.Decimal
                                            else -> KeyboardType.Number
                                        }
                                    ),
                                    textStyle = MaterialTheme.typography.titleLarge.copy(
                                        color = style.primaryText,
                                        fontFamily = style.bodyFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center
                                    ),
                                    shape = RoundedCornerShape(style.actionRadius),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = style.primaryText,
                                        unfocusedTextColor = style.primaryText,
                                        focusedContainerColor = style.fieldSurface,
                                        unfocusedContainerColor = style.fieldSurface,
                                        focusedBorderColor = style.accent.copy(alpha = 0.55f),
                                        unfocusedBorderColor = style.stroke,
                                        cursorColor = style.accent
                                    )
                                )
                                NumberPickerStepButton(
                                    text = "+",
                                    enabled = inputValueOrCurrent() < safeMax,
                                    palette = palette,
                                    onClick = {
                                        currentValue = (inputValueOrCurrent() + 1).coerceAtMost(safeMax)
                                        inputText = formatPickerValue(currentValue, isDecimalMode)
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (usePercentSlider) {
                                "$safeMin% - $safeMax%"
                            } else {
                                "${formatPickerValue(safeMin, isDecimalMode)} - ${formatPickerValue(safeMax, isDecimalMode)}"
                            },
                            color = style.secondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        val customCallback = onCustom
                        if (customText != null && customCallback != null) {
                            LegadoMiuixActionButton(
                                text = customText,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    customCallback.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        onPositive?.let { callback ->
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    callback.invoke(commitValue())
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            value: Int,
            minValue: Int,
            maxValue: Int,
            isDecimalMode: Boolean = false,
            positiveText: String,
            negativeText: String,
            customText: String? = null,
            onPositive: (Int) -> Unit,
            onCustom: (() -> Unit)? = null
        ): ComposeNumberPickerDialog {
            return ComposeNumberPickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putInt(ARG_VALUE, value)
                    putInt(ARG_MIN_VALUE, minValue)
                    putInt(ARG_MAX_VALUE, maxValue)
                    putBoolean(ARG_DECIMAL_MODE, isDecimalMode)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_CUSTOM_TEXT, customText)
                }
                this.onPositive = onPositive
                this.onCustom = onCustom
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_VALUE = "value"
        private const val ARG_MIN_VALUE = "minValue"
        private const val ARG_MAX_VALUE = "maxValue"
        private const val ARG_DECIMAL_MODE = "decimalMode"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_CUSTOM_TEXT = "customText"
    }
}

@Composable
private fun NumberPickerStepButton(
    text: String,
    enabled: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit
) {
    val actionRadius = palette.actionRadius ?: LocalContext.current.composeActionRadius()
    LegadoMiuixCard(
        modifier = Modifier
            .width(48.dp)
            .height(52.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) palette.surfaceVariant else palette.surfaceVariant.copy(alpha = 0.42f),
        contentColor = if (enabled) palette.primaryText else palette.secondaryText.copy(alpha = 0.52f),
        cornerRadius = actionRadius,
        insidePadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) palette.primaryText else palette.secondaryText.copy(alpha = 0.52f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatPickerValue(value: Int, decimalMode: Boolean): String {
    return if (decimalMode) {
        (value / 10.0).toString()
    } else {
        value.toString()
    }
}

private fun parsePickerValue(value: String, decimalMode: Boolean): Int? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (decimalMode) {
        normalized.toDoubleOrNull()
            ?.takeIf { java.lang.Double.isFinite(it) }
            ?.let { (it * 10).roundToInt() }
    } else {
        normalized.toIntOrNull()
    }
}

class ComposeMultiChoiceDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onPositive: ((BooleanArray) -> Unit)? = null
    private var onItemCheckedChange: ((Int, Boolean) -> Unit)? = null
    private var onDismissAction: (() -> Unit)? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissAction?.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null && onItemCheckedChange == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val itemLabels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val initialCheckedValues = remember(itemLabels) {
                    val initialChecked = args.getBooleanArray(ARG_CHECKED) ?: booleanArrayOf()
                    List(itemLabels.size) { index -> initialChecked.getOrNull(index) ?: false }
                }
                val saveCheckedState = itemLabels.size <= MAX_SAVEABLE_MULTI_CHOICE_ITEMS
                val saveableChecked = if (saveCheckedState) {
                    rememberSaveable(itemLabels) { mutableStateOf(initialCheckedValues) }
                } else {
                    null
                }
                val localChecked = if (saveCheckedState) {
                    null
                } else {
                    remember(itemLabels) {
                        mutableStateListOf<Boolean>().apply { addAll(initialCheckedValues) }
                    }
                }
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSubmit = onPositive != null
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(itemLabels) { index, label ->
                                LegadoMiuixChoiceRow(
                                    text = label,
                                    selected = saveableChecked?.value?.getOrNull(index)
                                        ?: localChecked?.getOrNull(index)
                                        ?: false,
                                    palette = palette,
                                    onClick = {
                                        if (index in itemLabels.indices) {
                                            val nextChecked = !(saveableChecked?.value?.getOrNull(index)
                                                ?: localChecked?.getOrNull(index)
                                                ?: false)
                                            val state = saveableChecked
                                            if (state != null) {
                                                state.value = state.value.toggleAt(index, itemLabels.size)
                                            } else {
                                                localChecked?.let { values ->
                                                    if (index in values.indices) {
                                                        values[index] = nextChecked
                                                    }
                                                }
                                            }
                                            onItemCheckedChange?.invoke(index, nextChecked)
                                        }
                                    },
                                    minHeight = 42.dp
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        if (canSubmit) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    val result = BooleanArray(itemLabels.size) { index ->
                                        saveableChecked?.value?.getOrNull(index)
                                            ?: localChecked?.getOrNull(index)
                                            ?: false
                                    }
                                    dismissAllowingStateLoss()
                                    onPositive?.invoke(result)
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            checked: BooleanArray,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            onItemCheckedChange: ((Int, Boolean) -> Unit)? = null,
            onDismissAction: (() -> Unit)? = null,
            onPositive: ((BooleanArray) -> Unit)? = null
        ): ComposeMultiChoiceDialog {
            val safeLabels = labels.toList()
            return ComposeMultiChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putBooleanArray(ARG_CHECKED, checked.copyOf())
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onPositive = onPositive
                this.onItemCheckedChange = onItemCheckedChange
                this.onDismissAction = onDismissAction
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_CHECKED = "checked"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}

private fun List<Boolean>.toggleAt(index: Int, size: Int): List<Boolean> {
    if (index !in 0 until size) return this
    return MutableList(size) { i -> getOrNull(i) ?: false }.apply {
        this[index] = !this[index]
    }
}

class ComposeConfirmDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    private var onPositive: (() -> Unit)? = null
    private var onNegative: (() -> Unit)? = null
    private var onNeutral: (() -> Unit)? = null
    private var onDismissAction: (() -> Unit)? = null
    private var handledAction = false

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!handledAction) onDismissAction?.invoke()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val neutralText = args.getString(ARG_NEUTRAL_TEXT)?.takeIf { it.isNotBlank() }
                val dangerPositive = args.getBoolean(ARG_DANGER_POSITIVE)
                val positiveRequiresCallback = args.getBoolean(ARG_POSITIVE_REQUIRES_CALLBACK, true)
                val negativeRequiresCallback = args.getBoolean(ARG_NEGATIVE_REQUIRES_CALLBACK, false)
                val messageInContent = args.getBoolean(ARG_MESSAGE_IN_CONTENT)
                val showNegative = args.getBoolean(ARG_SHOW_NEGATIVE, true)
                DismissWhenCallbackMissing(
                    missing = positiveRequiresCallback && onPositive == null ||
                        negativeRequiresCallback && onNegative == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    messageInContent = messageInContent,
                    content = {},
                    actions = {
                        val palette = style.toMiuixPalette()
                        var hasPriorAction = false
                        val neutralCallback = onNeutral
                        if (neutralText != null && neutralCallback != null) {
                            LegadoMiuixActionButton(
                                text = neutralText,
                                palette = palette,
                                onClick = {
                                    handledAction = true
                                    dismissAllowingStateLoss()
                                    neutralCallback.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            hasPriorAction = true
                        }
                        val negativeCallback = onNegative
                        if (showNegative && (!negativeRequiresCallback || negativeCallback != null)) {
                            if (hasPriorAction) {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            LegadoMiuixActionButton(
                                text = negativeText,
                                palette = palette,
                                onClick = {
                                    if (negativeCallback != null) handledAction = true
                                    dismissAllowingStateLoss()
                                    negativeCallback?.invoke()
                                },
                                cornerRadius = style.actionRadius
                            )
                            hasPriorAction = true
                        }
                        val positiveCallback = onPositive
                        if (!positiveRequiresCallback || positiveCallback != null) {
                            if (hasPriorAction) {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    handledAction = true
                                    dismissAllowingStateLoss()
                                    positiveCallback?.invoke()
                                },
                                primary = !dangerPositive,
                                danger = dangerPositive,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        /**
         * Lambda callbacks are intentionally transient. If Android recreates the dialog,
         * action buttons with lost callbacks are hidden instead of running stale work.
         */
        fun create(
            title: String,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            neutralText: String? = null,
            dangerPositive: Boolean = false,
            positiveRequiresCallback: Boolean = true,
            negativeRequiresCallback: Boolean = false,
            messageInContent: Boolean = false,
            showNegative: Boolean = true,
            onPositive: () -> Unit,
            onNegative: (() -> Unit)? = null,
            onNeutral: (() -> Unit)? = null,
            onDismissAction: (() -> Unit)? = null
        ): ComposeConfirmDialog {
            return ComposeConfirmDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putString(ARG_NEUTRAL_TEXT, neutralText)
                    putBoolean(ARG_DANGER_POSITIVE, dangerPositive)
                    putBoolean(ARG_POSITIVE_REQUIRES_CALLBACK, positiveRequiresCallback)
                    putBoolean(ARG_NEGATIVE_REQUIRES_CALLBACK, negativeRequiresCallback)
                    putBoolean(ARG_MESSAGE_IN_CONTENT, messageInContent)
                    putBoolean(ARG_SHOW_NEGATIVE, showNegative)
                }
                this.onPositive = onPositive
                this.onNegative = onNegative
                this.onNeutral = onNeutral
                this.onDismissAction = onDismissAction
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_NEUTRAL_TEXT = "neutralText"
        private const val ARG_DANGER_POSITIVE = "dangerPositive"
        private const val ARG_POSITIVE_REQUIRES_CALLBACK = "positiveRequiresCallback"
        private const val ARG_NEGATIVE_REQUIRES_CALLBACK = "negativeRequiresCallback"
        private const val ARG_MESSAGE_IN_CONTENT = "messageInContent"
        private const val ARG_SHOW_NEGATIVE = "showNegative"
    }
}

class ComposeSingleChoiceDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onPositive: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onPositive == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val itemLabels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val allowNoSelection = args.getBoolean(ARG_ALLOW_NO_SELECTION)
                var selectedIndex by rememberSaveable {
                    mutableStateOf(args.getInt(ARG_SELECTED_INDEX).coerceIn(-1, itemLabels.lastIndex))
                }
                val positiveText = args.getString(ARG_POSITIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.ok) }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSubmit = onPositive != null &&
                    (allowNoSelection || selectedIndex in itemLabels.indices)
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(itemLabels) { index, label ->
                                LegadoMiuixChoiceRow(
                                    text = label,
                                    selected = selectedIndex == index,
                                    palette = palette,
                                    onClick = { selectedIndex = index },
                                    minHeight = 42.dp
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        if (canSubmit) {
                            Spacer(modifier = Modifier.width(8.dp))
                            LegadoMiuixActionButton(
                                text = positiveText,
                                palette = palette,
                                onClick = {
                                    dismissAllowingStateLoss()
                                    onPositive?.invoke(selectedIndex)
                                },
                                primary = true,
                                cornerRadius = style.actionRadius
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        /**
         * Lambda callbacks are intentionally transient. If Android recreates the dialog,
         * the confirm action is hidden unless a callback is still attached.
         */
        fun create(
            title: String,
            labels: List<String>,
            selectedIndex: Int,
            message: String? = null,
            positiveText: String,
            negativeText: String,
            allowNoSelection: Boolean = false,
            onPositive: (Int) -> Unit
        ): ComposeSingleChoiceDialog {
            val safeLabels = labels.toList()
            return ComposeSingleChoiceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_MESSAGE, message)
                    putString(ARG_POSITIVE_TEXT, positiveText)
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                    putBoolean(ARG_ALLOW_NO_SELECTION, allowNoSelection)
                }
                this.onPositive = onPositive
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_SELECTED_INDEX = "selectedIndex"
        private const val ARG_MESSAGE = "message"
        private const val ARG_POSITIVE_TEXT = "positiveText"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
        private const val ARG_ALLOW_NO_SELECTION = "allowNoSelection"
    }
}

class ComposeActionListDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var onSelected: ((Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onSelected == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val style = rememberAppDialogStyle()
                val itemLabels = remember {
                    args.getStringArrayList(ARG_LABELS)?.toList().orEmpty()
                }
                val itemDescriptions = remember {
                    args.getStringArrayList(ARG_DESCRIPTIONS)?.toList().orEmpty()
                }
                val dangerIndices = remember {
                    args.getIntegerArrayList(ARG_DANGER_INDICES)?.toSet().orEmpty()
                }
                val negativeText = args.getString(ARG_NEGATIVE_TEXT)
                    .orEmpty()
                    .ifBlank { stringResource(R.string.cancel) }
                val canSelect = onSelected != null
                AppDialogFrame(
                    title = args.getString(ARG_TITLE).orEmpty(),
                    message = args.getString(ARG_MESSAGE),
                    scrollContent = false,
                    content = {
                        val palette = style.toMiuixPalette()
                        if (canSelect) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(itemLabels) { index, label ->
                                    LegadoMiuixActionRow(
                                        text = label,
                                        palette = palette,
                                        onClick = {
                                            dismissAllowingStateLoss()
                                            onSelected?.invoke(index)
                                        },
                                        description = itemDescriptions.getOrNull(index),
                                        danger = index in dangerIndices,
                                        cornerRadius = style.actionRadius
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = negativeText,
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            title: String,
            labels: List<String>,
            message: String? = null,
            descriptions: List<String> = emptyList(),
            dangerIndices: Set<Int> = emptySet(),
            negativeText: String,
            onSelected: (Int) -> Unit
        ): ComposeActionListDialog {
            require(labels.size <= MAX_ACTION_LIST_ITEMS) {
                "ComposeActionListDialog is for small action menus only."
            }
            val safeLabels = labels.toList()
            val safeDescriptions = List(safeLabels.size) { index ->
                descriptions.getOrNull(index).orEmpty()
            }
            val safeDangerIndices = dangerIndices.filterTo(linkedSetOf()) {
                it in safeLabels.indices
            }
            return ComposeActionListDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putStringArrayList(ARG_LABELS, ArrayList(safeLabels))
                    putString(ARG_MESSAGE, message)
                    putStringArrayList(ARG_DESCRIPTIONS, ArrayList(safeDescriptions))
                    putIntegerArrayList(ARG_DANGER_INDICES, ArrayList(safeDangerIndices))
                    putString(ARG_NEGATIVE_TEXT, negativeText)
                }
                this.onSelected = onSelected
            }
        }

        private const val ARG_TITLE = "title"
        private const val ARG_LABELS = "labels"
        private const val ARG_MESSAGE = "message"
        private const val ARG_DESCRIPTIONS = "descriptions"
        private const val ARG_DANGER_INDICES = "dangerIndices"
        private const val ARG_NEGATIVE_TEXT = "negativeText"
    }
}

@Composable
fun AppDialogSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = if (checked) style.primaryText else style.secondaryText,
                fontSize = 15.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                palette = palette
            )
        }
    }
}

@Composable
fun AppDialogOptionGroup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = style.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label ->
                LegadoMiuixChoiceRow(
                    text = label,
                    selected = selectedIndex == index,
                    palette = palette,
                    onClick = { onSelected(index) },
                    minHeight = 40.dp,
                    compact = true
                )
            }
        }
    }
}

@Composable
fun AppDialogSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showStepper: Boolean = false,
    step: Int = 1,
    valueFormatter: (Int) -> String = { it.toString() },
    onValueChangeFinished: (() -> Unit)? = null
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val safeStep = step.coerceAtLeast(1)
    val safeValue = value.coerceIn(range)
    val displayValue = valueFormatter(safeValue)
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(
            horizontal = if (compact) 11.dp else 13.dp,
            vertical = if (compact) 8.dp else 10.dp
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayValue,
                    color = style.secondaryText,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))
            AppThemedStepperSlider(
                value = safeValue,
                range = range,
                onValueChange = { onValueChange(it.coerceIn(range)) },
                palette = palette,
                step = safeStep,
                onValueChangeFinished = onValueChangeFinished
            )
        }
    }
}

data class AppDialogSliderItem(
    val title: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit,
    val showStepper: Boolean = false,
    val step: Int = 1,
    val valueFormatter: (Int) -> String = { it.toString() },
    val onValueChangeFinished: (() -> Unit)? = null
)

@Composable
fun AppDialogSliderGrid(
    items: List<AppDialogSliderItem>,
    modifier: Modifier = Modifier,
    minTwoColumnWidth: Dp = 330.dp
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= minTwoColumnWidth
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (twoColumns) {
                items.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            AppDialogSliderRow(
                                title = item.title,
                                value = item.value,
                                range = item.range,
                                onValueChange = item.onValueChange,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                showStepper = item.showStepper,
                                step = item.step,
                                valueFormatter = item.valueFormatter,
                                onValueChangeFinished = item.onValueChangeFinished
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items.forEach { item ->
                    AppDialogSliderRow(
                        title = item.title,
                        value = item.value,
                        range = item.range,
                        onValueChange = item.onValueChange,
                        showStepper = item.showStepper,
                        step = item.step,
                        valueFormatter = item.valueFormatter,
                        onValueChangeFinished = item.onValueChangeFinished
                    )
                }
            }
        }
    }
}

class ComposeFetchedModelDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var onAddSingle: ((String) -> Unit)? = null
    private var onAddSelected: ((List<String>) -> Unit)? = null
    private var onAddAll: ((List<String>) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DismissWhenCallbackMissing(
                    missing = onAddSingle == null,
                    dismiss = ::dismissAllowingStateLoss
                )
                val modelIds = remember {
                    args.getStringArrayList(ARG_MODEL_IDS)?.toList().orEmpty()
                }
                val existingIds = remember {
                    args.getStringArrayList(ARG_EXISTING_IDS)?.toSet().orEmpty()
                }
                io.legado.app.ui.config.FetchedModelSelectorContent(
                    modelIds = modelIds,
                    existingIds = existingIds,
                    onAddSingle = { modelId ->
                        dismissAllowingStateLoss()
                        onAddSingle?.invoke(modelId)
                    },
                    onAddSelected = { selectedIds ->
                        dismissAllowingStateLoss()
                        onAddSelected?.invoke(selectedIds)
                    },
                    onAddAll = { allIds ->
                        dismissAllowingStateLoss()
                        onAddAll?.invoke(allIds)
                    },
                    onDismiss = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    companion object {
        fun create(
            modelIds: List<String>,
            existingIds: Set<String>,
            onAddSingle: (String) -> Unit,
            onAddSelected: (List<String>) -> Unit,
            onAddAll: (List<String>) -> Unit
        ): ComposeFetchedModelDialog {
            return ComposeFetchedModelDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_MODEL_IDS, ArrayList(modelIds))
                    putStringArrayList(ARG_EXISTING_IDS, ArrayList(existingIds))
                }
                this.onAddSingle = onAddSingle
                this.onAddSelected = onAddSelected
                this.onAddAll = onAddAll
            }
        }

        private const val ARG_MODEL_IDS = "modelIds"
        private const val ARG_EXISTING_IDS = "existingIds"
    }
}
