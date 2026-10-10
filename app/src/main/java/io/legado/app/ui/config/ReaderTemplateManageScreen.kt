package io.legado.app.ui.config

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.ReaderTemplateManager
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppPackageManageActionButton
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen

/**
 * 阅读页面模板管理页（epub-md-rich-rendering 阶段 4.8a）。
 *
 * UI 归属（`docs/specs/epub-md-rich-rendering/ui-design.md` §二 U1）：
 * 设置域管理页族 —— 复用 `AppPackageManageScreen` / `AppManagementCard`（取色走
 * `rememberAppManagementPalette`，**不硬编码色**），与先例 `ShareNoteTemplateManageActivity` 同构。
 *
 * 与「阅读页样式」的关系：模板决定正文排版，样本页设置项管原生排版；
 * 模板生效时的排版优先级规则由 4.8d 的 `ReadStyleDialog` 承担，本页只负责"选哪套"。
 */
@Composable
internal fun ReaderTemplateManageScreen(
    entries: List<ReaderTemplateManager.Entry>,
    catalogErrors: List<String>,
    appliedId: String,
    effectiveId: String?,
    templatesEnabled: Boolean,
    onToggleTemplates: () -> Unit,
    onApply: (ReaderTemplateManager.Entry) -> Unit,
    onApplyFollowTheme: () -> Unit,
    onEdit: (ReaderTemplateManager.Entry) -> Unit,
    onMoreActions: (ReaderTemplateManager.Entry) -> List<AppManagementMenuAction>,
    onAddClick: () -> Unit,
    onRestoreDefaults: () -> Unit
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        isNightMode = false,
        summaryText = "管理阅读页模板：可应用、复制、编辑、导入导出与删除。当前作用于本地 Markdown 阅读；在线正文 / txt 随文本渲染模式一并开放（出版 EPUB 不适用）。",
        addText = "添加模板",
        onSwitchDayNight = {},
        onAdd = onAddClick,
        showDayNightTabs = false,
        headerContent = { palette ->
            item {
                CurrentTemplateCard(
                    entries = entries,
                    catalogErrors = catalogErrors,
                    appliedId = appliedId,
                    effectiveId = effectiveId,
                    templatesEnabled = templatesEnabled,
                    palette = palette,
                    onToggleTemplates = onToggleTemplates,
                    onApplyFollowTheme = onApplyFollowTheme,
                    onRestoreDefaults = onRestoreDefaults
                )
            }
        }
    ) { palette ->
        items(entries, key = { it.id }) { entry ->
            val active = appliedId == entry.id
            AppPackageManageItemCard(
                title = entry.name,
                info = buildInfoText(entry),
                isActive = active,
                canEdit = entry.source == ReaderTemplateManager.Source.USER,
                applyText = if (active) appliedText else applyText,
                editText = editText,
                moreActions = onMoreActions(entry),
                palette = palette,
                onApply = { onApply(entry) },
                onEdit = { onEdit(entry) }
            )
        }
    }
}

@Composable
private fun CurrentTemplateCard(
    entries: List<ReaderTemplateManager.Entry>,
    catalogErrors: List<String>,
    appliedId: String,
    effectiveId: String?,
    templatesEnabled: Boolean,
    palette: AppManagementPalette,
    onToggleTemplates: () -> Unit,
    onApplyFollowTheme: () -> Unit,
    onRestoreDefaults: () -> Unit
) {
    val followTheme = appliedId.isBlank()
    val effectiveName = entries.firstOrNull { it.id == effectiveId }?.name
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = if (templatesEnabled) "页面模板：已启用" else "页面模板：未启用",
            color = palette.settings.primaryText,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (templatesEnabled) {
                "开启后，在线正文 / 本地 txt / md 按选定模板渲染；关闭则完全回到原有排版。"
            } else {
                "当前未启用：阅读页完全按原有排版显示，下列选择暂不生效。"
            },
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = if (followTheme) {
                "跟随主题：浅色用素笺、夜间用霓虹夜行，随日夜自动切换。"
            } else {
                "已指定模板：${entries.firstOrNull { it.id == appliedId }?.name ?: appliedId}"
            },
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = when {
                effectiveName != null -> "当前选定：$effectiveName"
                else -> "当前无可用模板"
            },
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (catalogErrors.isNotEmpty()) {
            // 加载诊断必须可见：内置文件缺失/用户库损坏时不能静默显示成"模板变少了"
            Text(
                text = "加载提示：" + catalogErrors.joinToString("；"),
                color = palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppPackageManageActionButton(
                text = if (templatesEnabled) "关闭页面模板" else "启用页面模板",
                palette = palette.miuix,
                selected = templatesEnabled,
                onClick = onToggleTemplates
            )
            AppPackageManageActionButton(
                text = "跟随主题",
                palette = palette.miuix,
                selected = followTheme,
                onClick = onApplyFollowTheme
            )
            AppPackageManageActionButton(
                text = "恢复默认",
                palette = palette.miuix,
                onClick = onRestoreDefaults
            )
        }
    }
}

private fun buildInfoText(entry: ReaderTemplateManager.Entry): String {
    val parts = buildList {
        add(if (entry.isScroll) "滚动" else "分页")
        add(if (entry.source == ReaderTemplateManager.Source.BUILTIN) "内置" else "用户")
        entry.template.description.takeIf { it.isNotBlank() }?.let { add(it) }
        val warned = entry.issues.count { it.level != ReaderTemplateManager.Level.INFO }
        if (warned > 0) add("$warned 项导入提示")
    }
    return parts.joinToString(" · ")
}