package io.legado.app.ui.config

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 校验设置类弹窗的**公共行组件族**（书源 [CheckSourceConfig] / 订阅源 [CheckRssSourceConfig] 同构复用）。
 *
 * 为什么抽出来：两个弹窗的「超时数字输入行 / 布尔选择行 / 域名校验方式单选行」逐字相同，各自内联复制
 * 会在取色、行高、紧凑度上产生漂移（同构设计规范：先抽公共组件族再复用）。取色一律走 [AppDialogStyle]
 * 语义 token，不引入任何硬编码色，保持与主题设置体系一致。
 */
@Composable
internal fun CheckConfigNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    )
}

@Composable
internal fun CheckConfigChoiceRow(
    text: String,
    selected: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    LegadoMiuixChoiceRow(
        text = text,
        selected = selected,
        palette = style.toMiuixPalette(),
        onClick = onClick,
        minHeight = 40.dp,
        compact = true,
        enabled = enabled
    )
}

@Composable
internal fun CheckConfigDomainModeRow(
    text: String,
    selected: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            modifier = Modifier.weight(1f)
        )
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = style.accent,
                unselectedColor = style.secondaryText
            )
        )
    }
}

/** 超时输入行的数字过滤（仅 ASCII 数字；中文输入法/全角数字一律剔除）。两个校验弹窗共用。 */
internal fun String.filterAsciiDigits(): String = filter { it in '0'..'9' }
