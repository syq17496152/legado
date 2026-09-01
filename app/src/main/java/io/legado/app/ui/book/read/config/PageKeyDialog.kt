package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

/**
 * 自定义翻页键（deep-fix F 迁移：ComponentDialog+ComposeView → ComposeDialogFragment + AppDialogFrame 标准壳）
 */
class PageKeyDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    private var prevKeys by mutableStateOf("")
    private var nextKeys by mutableStateOf("")
    private var focusedField by mutableStateOf(PageKeyField.None)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        prevKeys = requireContext().getPrefString(PreferKey.prevKeys).orEmpty()
        nextKeys = requireContext().getPrefString(PreferKey.nextKeys).orEmpty()
        focusedField = PageKeyField.None
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    PageKeyContent(
                        prevKeys = prevKeys,
                        nextKeys = nextKeys,
                        onPrevChange = { prevKeys = it },
                        onNextChange = { nextKeys = it },
                        onFocusChange = { field, focused ->
                            if (focused) {
                                focusedField = field
                            } else if (focusedField == field) {
                                focusedField = PageKeyField.None
                            }
                        },
                        onReset = {
                            prevKeys = ""
                            nextKeys = ""
                        },
                        onConfirm = {
                            requireContext().putPrefString(PreferKey.prevKeys, prevKeys)
                            requireContext().putPrefString(PreferKey.nextKeys, nextKeys)
                            dismissAllowingStateLoss()
                        }
                    )
                }
            }
        }
    }
}

private enum class PageKeyField {
    None,
    Prev,
    Next
}

@Composable
private fun PageKeyContent(
    prevKeys: String,
    nextKeys: String,
    onPrevChange: (String) -> Unit,
    onNextChange: (String) -> Unit,
    onFocusChange: (PageKeyField, Boolean) -> Unit,
    onReset: () -> Unit,
    onConfirm: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.custom_page_key),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PageKeyTextField(
                    label = stringResource(R.string.prev_page_key),
                    value = prevKeys,
                    onValueChange = onPrevChange,
                    onHardwareKey = { onPrevChange(prevKeys.appendPageKey(it)) },
                    onFocusChanged = { focused ->
                        onFocusChange(PageKeyField.Prev, focused)
                    }
                )
                PageKeyTextField(
                    label = stringResource(R.string.next_page_key),
                    value = nextKeys,
                    onValueChange = onNextChange,
                    onHardwareKey = { onNextChange(nextKeys.appendPageKey(it)) },
                    onFocusChanged = { focused ->
                        onFocusChange(PageKeyField.Next, focused)
                    }
                )
                Text(
                    text = stringResource(R.string.page_key_set_help),
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp
                )
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.reset),
                palette = palette,
                onClick = onReset,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = onConfirm,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun PageKeyTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onHardwareKey: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val keyCode = event.key.nativeKeyCode
                    if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_DEL) {
                        onHardwareKey(keyCode)
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .onFocusChanged { state ->
                onFocusChanged(state.isFocused)
            },
        singleLine = true,
        label = { Text(label) },
        shape = RoundedCornerShape(style.actionRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedLabelColor = style.accent,
            unfocusedLabelColor = style.secondaryText
        ),
        textStyle = LocalTextStyle.current.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}

private fun String.appendPageKey(keyCode: Int): String {
    return if (isEmpty() || endsWith(",")) {
        this + keyCode
    } else {
        "$this,$keyCode"
    }
}
