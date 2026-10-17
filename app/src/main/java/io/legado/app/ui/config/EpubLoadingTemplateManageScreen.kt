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
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.help.config.Scene
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppPackageManageActionButton
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen

/**
 * 首屏加载模板管理页内容（Phase 3）。
 *
 * UI 归属：设置域管理页族 —— 复用 `AppPackageManageScreen` / `AppPackageManageItemCard`
 * （取色走 `rememberAppManagementPalette`，**不硬编码色**），与先例 `ReaderTemplateManageScreen` 同构。
 * 顶部状态卡（当前模板名 + 说明 + 「恢复默认」）为同族结构**自写**（不引用兄弟页 private 成员）。
 */
@Composable
internal fun EpubLoadingTemplateManageScreen(
    templates: List<EpubLoadingTemplate>,
    selectedId: String,
    onApply: (EpubLoadingTemplate) -> Unit,
    onEdit: (EpubLoadingTemplate) -> Unit,
    onMoreActions: (EpubLoadingTemplate) -> List<AppManagementMenuAction>,
    onAddClick: () -> Unit,
    onRestoreDefault: () -> Unit
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        isNightMode = false,
        summaryText = "开书首屏（整章加载）时显示的画面模板：可应用、预览、导入导出与删除；模板不可用时自动回落原有遮罩。",
        addText = "导入模板（JSON）",
        onSwitchDayNight = {},
        onAdd = onAddClick,
        showDayNightTabs = false,
        headerContent = { palette ->
            item {
                CurrentLoadingTemplateCard(
                    templates = templates,
                    selectedId = selectedId,
                    palette = palette,
                    onRestoreDefault = onRestoreDefault
                )
            }
        }
    ) { palette ->
        items(templates, key = { it.id }) { template ->
            val active = template.id == selectedId
            AppPackageManageItemCard(
                title = template.name,
                info = buildInfoText(template),
                isActive = active,
                canEdit = !template.builtIn,
                applyText = if (active) appliedText else applyText,
                editText = editText,
                moreActions = onMoreActions(template),
                palette = palette,
                onApply = { onApply(template) },
                onEdit = { onEdit(template) }
            )
        }
    }
}

@Composable
private fun CurrentLoadingTemplateCard(
    templates: List<EpubLoadingTemplate>,
    selectedId: String,
    palette: AppManagementPalette,
    onRestoreDefault: () -> Unit
) {
    val isDefault = selectedId == EpubLoadingTemplate.DEFAULT.id
    val current = templates.firstOrNull { it.id == selectedId } ?: EpubLoadingTemplate.DEFAULT
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = "当前首屏加载模板",
            color = palette.settings.primaryText,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = current.name,
            color = palette.settings.primaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (current.description.isNotBlank()) {
            Text(
                text = current.description,
                color = palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = if (isDefault) "当前为默认模板" else "已指定模板",
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppPackageManageActionButton(
                text = "恢复默认",
                palette = palette.miuix,
                onClick = onRestoreDefault
            )
        }
    }
}

/** 卡片副标题：内置/自定义 + 场景 + 说明。 */
private fun buildInfoText(template: EpubLoadingTemplate): String = buildList {
    add(if (template.builtIn) "内置" else "自定义")
    add("场景：" + template.scene.displayLabel())
    template.description.takeIf { it.isNotBlank() }?.let { add(it) }
}.joinToString(" · ")

/** 场景中文名（卡片副标题用）。 */
private fun Scene.displayLabel(): String = when (this) {
    Scene.INK -> "墨晕"
    Scene.ORBIT -> "星轨"
    Scene.BLOOM -> "花笺"
}
