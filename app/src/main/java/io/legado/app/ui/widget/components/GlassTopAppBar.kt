package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor

/**
 * 顶栏（S2/S4 统一容器）。
 *
 * 主题联动（主题统一 AD-19）：默认容器色跟随「颜色主题」的 colorPrimary（与 View 体系
 * TitleBar 一致），阴影高度跟随 barElevation 设置（View 侧 context.elevation，px→dp）。
 * 特殊页面（视频/阅读等需自定义底色/无阴影）可传 [containerColor] / [elevation] 覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: String,
    navIcon: ImageVector? = null,
    onNavClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color? = null,
    elevation: Dp? = null
) {
    val context = LocalContext.current
    // 默认容器色：跟随「颜色主题」colorPrimary（对齐 View TitleBar），可覆盖
    val barColor = containerColor ?: Color(context.primaryColor)
    // 默认阴影：跟随 barElevation 设置（View 侧 context.elevation，px→dp），可覆盖
    val barElevation = elevation ?: with(LocalDensity.current) { context.elevation.toDp() }
    // 内容色：按容器色亮度取黑/白（亮底黑 / 暗底白），与 View Toolbar 对比度一致
    val contentColor = contrastOn(barColor)
    TopAppBar(
        // M3(1.2.0+) 已移除 elevation 参数，用 Modifier.shadow 实现 barElevation 阴影
        modifier = Modifier.shadow(barElevation),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = barColor,
            scrolledContainerColor = barColor,
            navigationIconContentColor = contentColor,
            titleContentColor = contentColor,
            actionIconContentColor = contentColor
        ),
        title = {
            Text(
                text = title,
                // 对齐 View 体系 ToolbarTitle 20sp（LegadoTypography.titleLarge）
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1
            )
        },
        navigationIcon = {
            if (navIcon != null && onNavClick != null) {
                IconButton(onClick = onNavClick) {
                    Icon(navIcon, contentDescription = null)
                }
            }
        },
        actions = actions
    )
}
