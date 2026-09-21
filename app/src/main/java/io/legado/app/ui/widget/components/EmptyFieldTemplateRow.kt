package io.legado.app.ui.widget.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 空字段模板项：`label` = chip 文案，`code` = 点按后注入的**带注释可运行骨架**。
 */
data class FieldTemplate(val label: String, val code: String)

/**
 * 空字段模板引导行（F63，段落规则编辑页首个落地）。
 *
 * 语义边界：**只在字段为空时**由调用方渲染（本组件不持显隐状态、不读任何偏好）——
 * 把「从零写」降级为「改模板」，模板注释即文档；已编辑态不出现，避免干扰。
 * 视觉复用胶囊单源 [AppFilterChip]（`selected` 恒 false：一次性动作、无选中态），不新造胶囊变体。
 *
 * 取色走 [rememberAppSettingPalette]（禁止页内自建取色链）。
 */
@Composable
fun EmptyFieldTemplateRow(
    title: String,
    templates: List<FieldTemplate>,
    onPick: (FieldTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberAppSettingPalette()
    Column(modifier = modifier.padding(bottom = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = palette.secondaryText,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            templates.forEach { template ->
                AppFilterChip(
                    text = template.label,
                    selected = false,
                    onClick = { onPick(template) },
                )
            }
        }
    }
}