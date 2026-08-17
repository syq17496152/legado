package io.legado.app.ui.widget.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.transparentNavBar
import io.legado.app.ui.theme.ThemeSync

data class PillNavTab(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val badgeCount: Int = 0
)

/**
 * 底部导航栏（AD-17，V-1 真机确认方向；2026-08-14 按 bug① 简化）。
 *
 * 主题联动：背景跟随「导航栏颜色 colorBottomBackground」，开启「沉浸导航栏
 * transparentNavBar」时透明透出背景图，阴影跟随 barElevation（对齐原版
 * ThemeBottomNavigationVIew）；各 Tab weight 均分。
 * 选中态：图标/文字变色 + 文字加粗（简洁贴底，无底色胶囊动画）。
 * badgeCount>0 时右上角挂 BadgeDot，count=-1 时挂纯圆点。
 */
@Composable
fun PillNavigationBar(
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    tabs: List<PillNavTab>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true
) {
    val context = LocalContext.current
    // 订阅全局主题信号：bump 后重组，重读 bottomBackground/transparentNavBar/elevation/primary
    val themeVersion = ThemeSync.version
    // 选中色跟随「主色 colorPrimary」（对齐原版 BottomNavigationView 语义与 View TitleBar 顶栏色，
    // 避免 accent 主题下出现「蓝色顶栏+红色选中」的撞色不协调）
    val primary = Color(context.primaryColor)
    // 主题联动：背景跟随「导航栏颜色 colorBottomBackground」，开启「沉浸导航栏
    // transparentNavBar」时透明透出背景图；阴影跟随 barElevation（对齐原版
    // ThemeBottomNavigationVIew：bottomBackground + elevation=context.elevation）
    val transparentNavBar = context.transparentNavBar
    val barBackground = if (transparentNavBar) {
        Color.Transparent
    } else {
        Color(context.bottomBackground)
    }
    val barElevation = with(LocalDensity.current) { context.elevation.toDp() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (transparentNavBar) 0.dp else barElevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBackground)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                PillNavItem(
                    tab = tab,
                    selected = index == selectedTab,
                    showLabel = showLabels,
                    onClick = { onTabSelect(index) },
                    iconNormalColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSelectedColor = primary,
                    labelColor = primary
                )
            }
        }
    }
}

@Composable
private fun RowScope.PillNavItem(
    tab: PillNavTab,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    iconNormalColor: androidx.compose.ui.graphics.Color,
    iconSelectedColor: androidx.compose.ui.graphics.Color,
    labelColor: androidx.compose.ui.graphics.Color
) {
    val iconColor by animateColorAsState(
        targetValue = if (selected) iconSelectedColor else iconNormalColor,
        animationSpec = tween(durationMillis = 200),
        label = "pillIconColor"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) labelColor else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 200),
        label = "pillTextColor"
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .height(26.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.icon,
                contentDescription = tab.label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            if (tab.badgeCount != 0) {
                BadgeDot(
                    count = tab.badgeCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 6.dp, top = 0.dp)
                )
            }
        }
        if (showLabel) {
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
