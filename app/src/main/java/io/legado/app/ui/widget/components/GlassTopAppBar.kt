package io.legado.app.ui.widget.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.elevation
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.ui.theme.ThemeSync
import java.io.File

/**
 * 顶栏（S2/S4 统一容器）。
 *
 * 主题联动（主题统一 AD-19）：默认容器色跟随「颜色主题」colorPrimary（与 View 体系
 * TitleBar 一致），阴影高度跟随 barElevation 设置（View 侧 context.elevation，px→dp）。
 * 特殊页面（视频/阅读等需自定义底色/无阴影）可传 [containerColor] / [elevation] 覆盖。
 *
 * H13（2026-08-27 用户裁决）：对齐 MainTopBarView 消费「顶栏管理」TopBarConfig——
 * 仅 `STYLE_REGULAR` 顶栏包启用时应用背景色/壁纸/圆角/透明度；默认样式维持 colorPrimary 现状，
 * 避免既有页面顶栏默认态被改成黑/白底。
 *
 * ⚠️ 死按钮防线（topbar-icon-semantics-fix AD-03）：[navIcon] 与 [onNavClick] 必须成对传入——
 * 仅当两者均非 null 时才渲染返回键；漏传 [onNavClick] 时导航图标会**静默不渲染**（无任何警告），
 * 导致页面无返回入口。调用方必须保证返回可达性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: String,
    subtitle: String? = null,
    navIcon: ImageVector? = null,
    onNavClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color? = null,
    elevation: Dp? = null
) {
    val context = LocalContext.current
    // 订阅全局主题信号：ThemeSync.bump() 后本组件重组，重读 primaryColor/elevation 最新值
    val themeVersion = ThemeSync.version
    val config = remember(themeVersion) {
        TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    }
    val isRegular = config.style == TopBarConfig.STYLE_REGULAR
    // 顶栏包背景：仅 regular 样式消费（对齐 MainTopBarView renderBackgroundLayer）
    val wallpaperFile = remember(config.wallpaperPath, themeVersion) {
        if (isRegular && !config.wallpaperPath.isNullOrBlank()) {
            TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
        } else {
            null
        }
    }
    // 简化说明: 壁纸全幅显示，crop 裁切按 MainTopBarView 视觉近似（精确对齐归后续统一组件）
    val wallpaper = remember(wallpaperFile) { wallpaperFile?.let(::decodeTopBarWallpaper) }
    val defaultColor = if (isRegular) {
        Color(TopBarConfig.withOpacity(TopBarConfig.resolveBackgroundColor(config), config.wallpaperAlpha))
    } else {
        Color(context.primaryColor)
    }
    // 默认容器色：跟随 TopBarConfig（regular）/「颜色主题」colorPrimary（默认），可覆盖
    val barColor = containerColor ?: defaultColor
    // 默认阴影：跟随 barElevation 设置（View 侧 context.elevation，px→dp），可覆盖
    val barElevation = elevation ?: with(LocalDensity.current) { context.elevation.toDp() }
    // 内容色：按容器色亮度取黑/白（亮底黑 / 暗底白），与 View Toolbar 对比度一致
    val contentColor = contrastOn(barColor)
    val cornerRadius = if (isRegular) TopBarConfig.cornerRadius(context, config) else 0f
    Box(modifier = Modifier.shadow(barElevation)) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = config.wallpaperAlpha.coerceIn(0, 100) / 100f,
                modifier = Modifier
                    .matchParentSize()
                    .then(if (cornerRadius > 0f) Modifier.clip(RoundedCornerShape(cornerRadius)) else Modifier)
            )
        }
        TopAppBar(
            modifier = if (cornerRadius > 0f) Modifier.clip(RoundedCornerShape(cornerRadius)) else Modifier,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (wallpaper != null) Color.Transparent else barColor,
                scrolledContainerColor = if (wallpaper != null) Color.Transparent else barColor,
                navigationIconContentColor = contentColor,
                titleContentColor = contentColor,
                actionIconContentColor = contentColor
            ),
            title = {
                if (subtitle != null) {
                    Column {
                        Text(
                            text = title,
                            // 对齐 View 体系 ToolbarTitle 20sp（LegadoTypography.titleLarge）
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            color = contentColor.copy(alpha = 0.8f)
                        )
                    }
                } else {
                    Text(
                        text = title,
                        // 对齐 View 体系 ToolbarTitle 20sp（LegadoTypography.titleLarge）
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1
                    )
                }
            },
            navigationIcon = {
                if (navIcon != null && onNavClick != null) {
                    IconButton(onClick = onNavClick) {
                        // 2.4（bookshelf-refresh-and-title-fix R4）：图标 20dp 档。
                        // 注：actions 为调用方传入的 Composable，M3 Icon 默认 24dp 无法在此中心化
                        // 缩放，action 图标维持 M3 默认（偏差登记 tasks.md AOAdapt/issue-list）
                        Icon(
                            navIcon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            actions = actions
        )
    }
}

/** 顶栏壁纸有界解码（防大图 OOM），与 ConfigActivity 顶栏同策略。 */
internal fun decodeTopBarWallpaper(file: File, maxDim: Int = 2048): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (sample * 2 <= maxDim.coerceAtLeast(1) &&
            (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim)
        ) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()
}

/**
 * H15（2026-08-28）：顶栏内容色统一取值（自绘/管理页顶栏接入 TopBarConfig 后共用）——
 * 「顶栏管理」STYLE_REGULAR 时按包背景（含透明度合成）对比度取黑/白（对齐 GlassTopAppBar），
 * 未启用回落主题 titleTextColor（与 View Toolbar/AppManagementTopBar 对比度口径一致）。
 */
@Composable
internal fun rememberTopBarContentColor(): Color {
    val context = LocalContext.current
    val themeVersion = ThemeSync.version
    val config = remember(themeVersion) {
        TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    }
    return if (config.style == TopBarConfig.STYLE_REGULAR) {
        contrastOn(
            Color(
                TopBarConfig.withOpacity(
                    TopBarConfig.resolveBackgroundColor(config),
                    config.wallpaperAlpha
                )
            )
        )
    } else {
        Color(context.titleTextColor)
    }
}