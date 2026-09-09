package io.legado.app.ui.book.read.config.casting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 选角模板管理 LIST 态（AD-10）：内置只读（查看/复制为自定义/启停）+自定义（编辑/删除/启停）
 * 空态引导；规则摘要=tag 计数（不展开 pattern 原文防超长）
 */
@Composable
fun TtsCastingListScreen(
    templates: List<TtsCastingTemplate>,
    onDismiss: () -> Unit,
    onOpenEditor: (TtsCastingTemplate) -> Unit,
    onCopyToCustom: (TtsCastingTemplate) -> Unit,
    onDelete: (TtsCastingTemplate) -> Unit,
    onToggleEnabled: (TtsCastingTemplate) -> Unit,
    onAdd: () -> Unit,
    onOpenImport: () -> Unit
) {
    val style = rememberAppDialogStyle()
    Column(
        Modifier
            .fillMaxWidth()
            .background(style.surface)
    ) {
        // 顶栏：关闭 + 标题 + 导入 + 新增
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_close_x),
                    contentDescription = stringResource(R.string.close),
                    tint = style.secondaryText
                )
            }
            Text(
                text = stringResource(R.string.tts_casting_manage_title),
                style = MaterialTheme.typography.titleMedium,
                color = style.primaryText,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onOpenImport) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_import),
                    contentDescription = stringResource(R.string.tts_casting_import_export),
                    tint = style.secondaryText
                )
            }
            IconButton(onClick = onAdd) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.tts_casting_add),
                    tint = style.secondaryText
                )
            }
        }
        if (templates.isEmpty()) {
            // 空态引导（S1-2）：明示"暂无可用模板"并给恢复路径
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.tts_casting_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = style.secondaryText
                    )
                    TextButton(onClick = onAdd) {
                        Text(stringResource(R.string.tts_casting_empty_action), color = style.accent)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(templates, key = { it.id }) { template ->
                    TtsCastingListRow(
                        template = template,
                        onOpen = { onOpenEditor(template) },
                        onCopy = { onCopyToCustom(template) },
                        onDelete = { onDelete(template) },
                        onToggle = { onToggleEnabled(template) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsCastingListRow(
    template: TtsCastingTemplate,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    val style = rememberAppDialogStyle()
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = style.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (template.builtin) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.tts_casting_builtin_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = style.secondaryText
                    )
                }
            }
            Text(
                text = ruleSummary(template),
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LegadoMiuixSwitch(
            checked = template.enabled,
            onCheckedChange = { onToggle() },
            palette = style.toMiuixPalette()
        )
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.more), color = style.secondaryText)
            }
            io.legado.app.ui.widget.components.AppDropdownMenu(
                expanded = expanded,
                onDismiss = { expanded = false },
                actions = buildList {
                    add(
                        io.legado.app.ui.widget.components.MenuAction(
                            title = stringResource(R.string.tts_casting_edit),
                            onClick = onOpen
                        )
                    )
                    if (template.builtin) {
                        add(
                            io.legado.app.ui.widget.components.MenuAction(
                                title = stringResource(R.string.tts_casting_copy_custom),
                                onClick = onCopy
                            )
                        )
                    } else {
                        add(
                            io.legado.app.ui.widget.components.MenuAction(
                                title = stringResource(R.string.tts_casting_delete),
                                onClick = onDelete
                            )
                        )
                    }
                }
            )
        }
    }
}

/** 规则摘要：tag 计数（不展开 pattern 原文防超长，S1 摘要口径） */
private fun ruleSummary(template: TtsCastingTemplate): String {
    val ruleSet = runCatching {
        CastingRuleSet.fromEntity(template)
    }.getOrNull()
    val rules = ruleSet?.rules ?: return ""
    if (rules.isEmpty()) return ""
    return rules.joinToString(" / ") { it.tag } + " (${rules.size})"
}

/**
 * IMPORT 态：导出（激活模板 JSON 展示+复制）+导入（粘贴 JSON+名称→校验链→结果明示）
 */
@Composable
fun TtsCastingImportScreen(
    message: String?,
    busy: Boolean,
    exportText: String?,
    onImport: (json: String, name: String) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onCopyExport: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    var importJson by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(style.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back), color = style.accent)
            }
            Text(
                text = stringResource(R.string.tts_casting_import_export),
                style = MaterialTheme.typography.titleMedium,
                color = style.primaryText
            )
        }
        // 导入区
        Text(
            text = stringResource(R.string.tts_casting_import_title),
            style = MaterialTheme.typography.labelLarge,
            color = style.secondaryText
        )
        OutlinedTextField(
            value = importJson,
            onValueChange = { importJson = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            placeholder = { Text(stringResource(R.string.tts_casting_import_hint), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = importName,
            onValueChange = { importName = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_casting_import_name_hint), style = MaterialTheme.typography.bodySmall) },
            singleLine = true
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = style.secondaryText,
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { onImport(importJson, importName) }, enabled = !busy && importJson.isNotBlank()) {
                Text(
                    if (busy) stringResource(R.string.tts_casting_importing) else stringResource(R.string.tts_casting_import_action),
                    color = style.accent
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // 导出区
        Text(
            text = stringResource(R.string.tts_casting_export_title),
            style = MaterialTheme.typography.labelLarge,
            color = style.secondaryText
        )
        Text(
            text = stringResource(R.string.tts_casting_export_hint),
            style = MaterialTheme.typography.bodySmall,
            color = style.secondaryText
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onExport) {
                Text(stringResource(R.string.tts_casting_export_action), color = style.accent)
            }
            exportText?.let { text ->
                TextButton(onClick = { onCopyExport(text) }) {
                    Text(stringResource(R.string.copy_text), color = style.accent)
                }
            }
        }
        exportText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
