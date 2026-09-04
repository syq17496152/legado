package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.subtitleLarge

@Composable
internal fun LibraryContainerManageScreen(
    containers: List<LibraryContainerConfig>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onItemClick: (LibraryContainerConfig) -> Unit,
    pageMenuActions: () -> List<AppManagementMenuAction>,
    onMoreActions: (LibraryContainerConfig) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar 顶栏与根 Surface）
        AppManagementScaffold(
            title = stringResource(R.string.library_container_manage_title),
            selectedCount = 0,
            totalCount = containers.size,
            palette = palette,
            onBack = onBack,
            topActions = listOf(
                AppManagementAction(
                    text = stringResource(R.string.more_menu),
                    menuActions = pageMenuActions
                )
            )
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "书库容器只用于同步阅读章节缓存，不参与备份、主题、气泡或缓存包同步。阅读时会先读取目录索引，只有命中缓存章节才请求正文。",
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 6.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    items(containers, key = { it.id }) { item ->
                        LibraryContainerCard(
                            item = item,
                            isDefault = LibraryContainerManager.selectedId() == item.id,
                            onClick = { onItemClick(item) },
                            moreActions = onMoreActions(item)
                        )
                    }
                }
                LegadoMiuixActionButton(
                    text = "添加书库容器",
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = true,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
private fun LibraryContainerCard(
    item: LibraryContainerConfig,
    isDefault: Boolean,
    onClick: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val container = item.container
    val displayName = LibraryContainerManager.displayLabel(item)
    val capacityMb = container.capacityMb.coerceAtLeast(0)
    val usedBytes = container.usedBytes.coerceAtLeast(0)
    val capacityText = if (capacityMb > 0) {
        val capacityBytes = LibraryContainerManageActivity.mbToBytes(capacityMb)
        "容量：${LibraryContainerManageActivity.formatBytes(capacityBytes)} / 已用：${LibraryContainerManageActivity.formatBytes(usedBytes)} / 剩余：${LibraryContainerManageActivity.formatBytes((capacityBytes - usedBytes).coerceAtLeast(0))} / 已满：${if (container.isFull) "是" else "否"}"
    } else {
        "已用：${LibraryContainerManageActivity.formatBytes(usedBytes)}（不限容量）"
    }
    val minUpload = if (item.minUploadChars > 0) "最少${item.minUploadChars}字" else "不过滤短章"
    val dailyLimit = if (item.dailyUploadLimit > 0) "每日${item.dailyUploadLimit}章" else "每日不限"
    val lockState = if (item.lockedImported) " · 加密导入" else ""
    val stateText = "状态：${if (container.enabled) "启用" else "禁用"}$lockState · 书源优先 · ${item.sourceUrls.size} 个书源 · $minUpload · $dailyLimit"

    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onClick,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "${container.bucket}/${container.prefix.trim('/')}",
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = capacityText,
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stateText,
                    color = if (container.enabled) palette.settings.accent else palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = if (container.enabled) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isDefault) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✓",
                    color = palette.settings.accent,
                    fontSize = MaterialTheme.typography.subtitleLarge.fontSize,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AppManagementMoreActionButton(
                actionsProvider = { moreActions },
                palette = palette,
                contentDescription = "更多"
            )
        }
    }
}
