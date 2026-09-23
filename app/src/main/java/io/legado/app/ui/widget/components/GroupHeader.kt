package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.ui.widget.compose.AppUiTokens

/**
 * 分组列表组头（AD-21：MoRealm BookSourceManageScreen 分组头部范式）。
 *
 * 折叠箭头（展开=下/收起=右）+ 组名（titleSmall Bold）+ 启用数/总数徽标 + 组操作「更多」菜单。
 * 折叠状态由调用方持用 [collapsed]（rememberSaveable），点组名行整体切换折叠。
 * 组操作 [onMenuActions] 由调用方构建（全部启用(N)/全部停用(N)，数量写进文案），内部用 [AppDropdownMenu] 承接。
 */
@Composable
fun GroupHeader(
    name: String,
    enabledCount: Int,
    totalCount: Int,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onMenuActions: (() -> List<MenuAction>)? = null,
    modifier: Modifier = Modifier,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxWidth()) {
        var menuExpanded by remember { mutableStateOf(false) }
        // R28 收口（2026-09-23）：本组件原用 M3 派生键（含 onSurfaceVariant）取色 ⇒ 换主题色时
        // 组头文字/徽标不跟随，且作为 J3 的「复用目标」会把失守传染给复用方。
        // 现改走面 token：文字 palette.primaryText / palette.secondaryText；计数徽标底 = chip 面
        // token tabBackgroundColor（ui-standards/color.md §六）。
        val palette = AppUiTokens.settingPalette()
        val themeUi = rememberThemeUiPalette()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClick = onToggleCollapse)
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = palette.secondaryText,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = palette.primaryText,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color(themeUi.tabBackgroundColor),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = if (enabledCount == totalCount) "$totalCount" else "$enabledCount/$totalCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.secondaryText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            if (onMenuActions != null) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = palette.secondaryText,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { menuExpanded = true }
                )
                AppDropdownMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actions = onMenuActions()
                )
            }
        }
        content?.invoke(this)
    }
}
