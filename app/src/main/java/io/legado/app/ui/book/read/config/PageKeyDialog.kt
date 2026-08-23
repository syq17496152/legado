package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
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
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout

class PageKeyDialog(private val context: Context) : ComponentDialog(context) {

    private var prevKeys by mutableStateOf(context.getPrefString(PreferKey.prevKeys).orEmpty())
    private var nextKeys by mutableStateOf(context.getPrefString(PreferKey.nextKeys).orEmpty())
    private var focusedField by mutableStateOf(PageKeyField.None)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(
            ComposeView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
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
                            context.putPrefString(PreferKey.prevKeys, prevKeys)
                            context.putPrefString(PreferKey.nextKeys, nextKeys)
                            dismiss()
                        }
                    )
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_DEL) {
            when (focusedField) {
                PageKeyField.Prev -> {
                    prevKeys = prevKeys.appendPageKey(keyCode)
                    return true
                }

                PageKeyField.Next -> {
                    nextKeys = nextKeys.appendPageKey(keyCode)
                    return true
                }

                PageKeyField.None -> Unit
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dismiss() {
        super.dismiss()
        currentFocus?.hideSoftInput()
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
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text(
                text = stringResource(R.string.custom_page_key),
                color = style.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = style.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(16.dp))
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
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.page_key_set_help),
                color = style.secondaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.reset),
                    palette = palette,
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    cornerRadius = style.actionRadius,
                    minWidth = 0.dp
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.ok),
                    palette = palette,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    primary = true,
                    cornerRadius = style.actionRadius,
                    minWidth = 0.dp
                )
            }
        }
    }
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
