package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import java.util.Locale

/**
 * Dialog for editing bubble config fields (name, colors, size scale, SVG template).
 *
 * Color picking is delegated to the caller via [onPickColor]. The caller should:
 * 1. Show a [com.jaredrummler.android.colorpicker.ColorPickerDialog]
 * 2. In [com.jaredrummler.android.colorpicker.ColorPickerDialogListener.onColorSelected],
 *    update [editingConfig] and re-show this dialog via [showEditDialog].
 *
 * Size scale picking and SVG editing are also delegated to the caller.
 */
class BubbleEditDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    /** Called when the user confirms the edit. Receives the name and current editing config. */
    private var onSaved: ((name: String, config: BubblePackageManager.Config) -> Unit)? = null
    private var onNameChanged: ((String) -> Unit)? = null
    /** Called when the user taps the SVG template row. */
    private var onOpenSvgEditor: (() -> Unit)? = null
    /** Called when the user taps the size scale row. */
    private var onOpenSizeScalePicker: (() -> Unit)? = null
    /**
     * Called when the user taps a color row.
     * @param dialogId one of [COLOR_DAY_NORMAL], [COLOR_DAY_EMPHASIS], etc.
     * @param currentColor the current color as an ARGB int.
     */
    private var onPickColor: ((dialogId: Int, currentColor: Int) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        val configName = args.getString(ARG_NAME).orEmpty()
        val sizeScaleDisplay = args.getString(ARG_SIZE_SCALE_DISPLAY).orEmpty()
        val dayNormal = args.getString(ARG_DAY_NORMAL).orEmpty()
        val dayEmphasis = args.getString(ARG_DAY_EMPHASIS).orEmpty()
        val nightNormal = args.getString(ARG_NIGHT_NORMAL).orEmpty()
        val nightEmphasis = args.getString(ARG_NIGHT_EMPHASIS).orEmpty()
        val titleText = args.getString(ARG_TITLE).orEmpty()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BubbleEditContent(
                    title = titleText,
                    initialName = configName,
                    sizeScaleDisplay = sizeScaleDisplay,
                    dayNormal = dayNormal,
                    dayEmphasis = dayEmphasis,
                    nightNormal = nightNormal,
                    nightEmphasis = nightEmphasis,
                    onNameChanged = { name ->
                        onNameChanged?.invoke(name)
                    },
                    onDismiss = { dismissAllowingStateLoss() },
                    onSave = { name ->
                        dismissAllowingStateLoss()
                        val config = editingConfigSnapshot().copy(name = name)
                        onSaved?.invoke(name, config)
                    },
                    onOpenSizeScalePicker = {
                        dismissAllowingStateLoss()
                        onOpenSizeScalePicker?.invoke()
                    },
                    onOpenSvgEditor = {
                        dismissAllowingStateLoss()
                        onOpenSvgEditor?.invoke()
                    },
                    onPickColor = { dialogId, currentColor ->
                        onPickColor?.invoke(dialogId, currentColor)
                    }
                )
            }
        }
    }

    /**
     * Build a Config snapshot from the arguments bundle.
     */
    private fun editingConfigSnapshot(): BubblePackageManager.Config {
        val args = arguments ?: Bundle()
        return BubblePackageManager.Config(
            name = args.getString(ARG_NAME).orEmpty(),
            svgTemplate = args.getString(ARG_SVG).orEmpty(),
            sizeScale = args.getFloat(ARG_SIZE_SCALE, 1f),
            dayNormalColor = args.getString(ARG_DAY_NORMAL),
            dayEmphasisColor = args.getString(ARG_DAY_EMPHASIS),
            nightNormalColor = args.getString(ARG_NIGHT_NORMAL),
            nightEmphasisColor = args.getString(ARG_NIGHT_EMPHASIS),
            updatedAt = System.currentTimeMillis()
        )
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_NAME = "name"
        private const val ARG_SVG = "svg"
        private const val ARG_SIZE_SCALE = "sizeScale"
        private const val ARG_SIZE_SCALE_DISPLAY = "sizeScaleDisplay"
        private const val ARG_DAY_NORMAL = "dayNormal"
        private const val ARG_DAY_EMPHASIS = "dayEmphasis"
        private const val ARG_NIGHT_NORMAL = "nightNormal"
        private const val ARG_NIGHT_EMPHASIS = "nightEmphasis"

        fun create(
            config: BubblePackageManager.Config,
            isAdd: Boolean,
            onSaved: (name: String, config: BubblePackageManager.Config) -> Unit,
            onNameChanged: (String) -> Unit,
            onOpenSizeScalePicker: () -> Unit,
            onOpenSvgEditor: () -> Unit,
            onPickColor: (dialogId: Int, currentColor: Int) -> Unit
        ): BubbleEditDialog {
            return BubbleEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, if (isAdd) "添加" else "编辑")
                    putString(ARG_NAME, config.name)
                    putString(ARG_SVG, config.svgTemplate)
                    putFloat(ARG_SIZE_SCALE, config.sizeScale)
                    putString(
                        ARG_SIZE_SCALE_DISPLAY,
                        "%.1f".format(Locale.ROOT, config.sizeScale)
                    )
                    putString(ARG_DAY_NORMAL, config.dayNormalColor)
                    putString(ARG_DAY_EMPHASIS, config.dayEmphasisColor)
                    putString(ARG_NIGHT_NORMAL, config.nightNormalColor)
                    putString(ARG_NIGHT_EMPHASIS, config.nightEmphasisColor)
                }
                this.onSaved = onSaved
                this.onNameChanged = onNameChanged
                this.onOpenSizeScalePicker = onOpenSizeScalePicker
                this.onOpenSvgEditor = onOpenSvgEditor
                this.onPickColor = onPickColor
            }
        }

        const val COLOR_DAY_NORMAL = 0x6811
        const val COLOR_DAY_EMPHASIS = 0x6812
        const val COLOR_NIGHT_NORMAL = 0x6813
        const val COLOR_NIGHT_EMPHASIS = 0x6814
    }
}

@Composable
private fun BubbleEditContent(
    title: String,
    initialName: String,
    sizeScaleDisplay: String,
    dayNormal: String?,
    dayEmphasis: String?,
    nightNormal: String?,
    nightEmphasis: String?,
    onNameChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onOpenSizeScalePicker: () -> Unit,
    onOpenSvgEditor: () -> Unit,
    onPickColor: (Int, Int) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var name by rememberSaveable { mutableStateOf(initialName) }

    AppDialogFrame(
        title = title,
        scrollContent = true,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Name field
                AppDialogEditField(
                    value = name,
                    onValueChange = {
                        name = it
                        onNameChanged(it)
                    },
                    label = "名称",
                    style = style
                )

                // Size scale row
                EditOptionRow(
                    title = "大小倍率",
                    value = sizeScaleDisplay,
                    style = style,
                    onClick = onOpenSizeScalePicker
                )

                // Color rows
                ColorOptionRow(
                    title = "日间常规色",
                    colorHex = dayNormal ?: BubblePackageManager.DEFAULT_NORMAL_COLOR,
                    style = style,
                    onClick = {
                        onPickColor(
                            BubbleEditDialog.COLOR_DAY_NORMAL,
                            parseColorInt(dayNormal, emphasis = false)
                        )
                    }
                )
                ColorOptionRow(
                    title = "日间强调色",
                    colorHex = dayEmphasis ?: BubblePackageManager.DEFAULT_EMPHASIS_COLOR,
                    style = style,
                    onClick = {
                        onPickColor(
                            BubbleEditDialog.COLOR_DAY_EMPHASIS,
                            parseColorInt(dayEmphasis, emphasis = true)
                        )
                    }
                )
                ColorOptionRow(
                    title = "夜间常规色",
                    colorHex = nightNormal ?: BubblePackageManager.DEFAULT_NORMAL_COLOR,
                    style = style,
                    onClick = {
                        onPickColor(
                            BubbleEditDialog.COLOR_NIGHT_NORMAL,
                            parseColorInt(nightNormal, emphasis = false)
                        )
                    }
                )
                ColorOptionRow(
                    title = "夜间强调色",
                    colorHex = nightEmphasis ?: BubblePackageManager.DEFAULT_EMPHASIS_COLOR,
                    style = style,
                    onClick = {
                        onPickColor(
                            BubbleEditDialog.COLOR_NIGHT_EMPHASIS,
                            parseColorInt(nightEmphasis, emphasis = true)
                        )
                    }
                )

                // SVG template row
                EditOptionRow(
                    title = "SVG 模板",
                    value = "点击编辑，支持 \${color} 和 \${num}",
                    style = style,
                    onClick = onOpenSvgEditor
                )
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onDismiss,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                primary = true,
                cornerRadius = style.actionRadius,
                onClick = { onSave(name) }
            )
        }
    )
}

@Composable
private fun AppDialogEditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke
            )
        )
    }
}

@Composable
private fun EditOptionRow(
    title: String,
    value: String,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                color = style.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ColorOptionRow(
    title: String,
    colorHex: String,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val colorInt = runCatching { colorHex.toColorInt() }
                .getOrDefault(0xFF808080.toInt())
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                modifier = Modifier.size(18.dp),
                shape = CircleShape,
                color = Color(colorInt),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = colorHex.uppercase(Locale.ROOT),
                color = style.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun parseColorInt(value: String?, emphasis: Boolean): Int {
    val fallback = if (emphasis) {
        BubblePackageManager.DEFAULT_EMPHASIS_COLOR
    } else {
        BubblePackageManager.DEFAULT_NORMAL_COLOR
    }
    val normalized = value?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { if (it.startsWith("#")) it else "#$it" }
        ?: fallback
    return runCatching { normalized.toColorInt() }.getOrDefault(fallback.toColorInt())
}
