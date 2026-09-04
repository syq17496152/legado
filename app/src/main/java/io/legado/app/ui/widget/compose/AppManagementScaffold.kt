package io.legado.app.ui.widget.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.titleTextColor
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.ThemeSync
import io.legado.app.ui.theme.subtitleLarge
import io.legado.app.ui.theme.subtitleLargeX
import io.legado.app.ui.widget.components.contrastOn
import io.legado.app.ui.widget.components.decodeTopBarWallpaper

data class AppManagementAction(
    val text: String,
    @param:DrawableRes val iconRes: Int? = null,
    // followup F5：icon 槽位（红队第 3 轮⑤：iconRes DrawableRes 与 ImageVector 不可逆转换）
    val icon: ImageVector? = null,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit = {},
    val menuActions: (() -> List<AppManagementMenuAction>)? = null
)

@Composable
fun AppManagementScaffold(
    title: String,
    selectedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    palette: AppManagementPalette = rememberAppManagementPalette(),
    searchQuery: String? = null,
    searchHint: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    topActions: List<AppManagementAction> = emptyList(),
    bottomActions: List<AppManagementAction> = emptyList(),
    onBack: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onInvertSelection: (() -> Unit)? = null,
    content: @Composable (AppManagementPalette) -> Unit
) {
    LegadoComposeTheme {
    // 根背景（followup F4 v3）：透明度>0 时叠半透明 backgroundColor（透出 decorView 底图/背景），0=原状透明
    val rootContext = LocalContext.current
    val bgAlpha = remember(ThemeSync.version) { AppConfig.manageBgAlphaFraction }
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (bgAlpha > 0f) Modifier.background(Color(rootContext.backgroundColor).copy(alpha = bgAlpha))
                else Modifier
            )
    ) {
        AppManagementTopBar(
            title = title,
            palette = palette,
            searchQuery = searchQuery,
            searchHint = searchHint,
            onSearchChange = onSearchChange,
            actions = topActions,
            onBack = onBack
        )
        Box(modifier = Modifier.weight(1f)) {
            content(palette)
        }
        AppManagementSelectionBottomBar(
            selectedCount = selectedCount,
            totalCount = totalCount,
            palette = palette,
            actions = bottomActions,
            onSelectAll = onSelectAll,
            onInvertSelection = onInvertSelection
        )
    }
    }
}

@Composable
private fun AppManagementTopBar(
    title: String,
    palette: AppManagementPalette,
    searchQuery: String?,
    searchHint: String?,
    onSearchChange: ((String) -> Unit)?,
    actions: List<AppManagementAction>,
    onBack: (() -> Unit)?
) {
    val context = LocalContext.current
    val themeVersion = ThemeSync.version
    // H15（2026-08-28）：接入「顶栏管理」TopBarConfig——顶栏管理配置优先（STYLE_REGULAR 消费
    // 背景色/壁纸/透明度/圆角，对齐 GlassTopAppBar/MainTopBarView renderBackgroundLayer），
    // 未启用回落 immersiveManageBar 开关（页面底色 vs 主题主色，H1 AD-01 保留 48dp 自绘形态）
    val config = remember(themeVersion) {
        TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    }
    val isRegular = config.style == TopBarConfig.STYLE_REGULAR
    val wallpaperFile = remember(config.wallpaperPath, themeVersion) {
        if (isRegular && !config.wallpaperPath.isNullOrBlank()) {
            TopBarConfig.currentWallpaperFile(context, AppConfig.isNightTheme)
        } else {
            null
        }
    }
    val wallpaper = remember(wallpaperFile) { wallpaperFile?.let(::decodeTopBarWallpaper) }
    // 顶栏背景三级决策链（ui-theme-governance-polish P5/AD-05）：
    // 显式自定义背景色（resolve 兜底后值比较）→ 沉浸开关 → 默认主色
    val topBarBase = when {
        TopBarConfig.hasCustomBackground(config) ->
            Color(TopBarConfig.resolveBackgroundColor(config))
        AppConfig.immersiveManageBar -> Color(context.backgroundColor)
        else -> Color(context.primaryColor)
    }
    // 内容色先于减淡决策（对比度基于不透明基色计算；非 REGULAR 由 titleTextColor
    // 统一为 contrastOn(基色)——红队 R3-P1-1/R2-P2-3）
    val topBarContentColor = contrastOn(topBarBase)
    val cornerRadius = if (isRegular) TopBarConfig.cornerRadius(context, config) else 0f
    // 顶栏着色（followup F4 v3）：有顶栏包壁纸走原 withOpacity 语义；否则半透明叠 backgroundColor
    // （透出全局底图/背景，0=不透明基色原状）
    val topBarColor = if (wallpaper != null && isRegular) {
        Color(TopBarConfig.withOpacity(TopBarConfig.resolveBackgroundColor(config), config.wallpaperAlpha))
    } else {
        topBarBase.copy(alpha = remember(themeVersion) { AppConfig.manageBgAlphaFraction })
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(topBarColor)
            .then(if (cornerRadius > 0f) Modifier.clip(RoundedCornerShape(cornerRadius)) else Modifier)
    ) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = config.wallpaperAlpha.coerceIn(0, 100) / 100f,
                modifier = Modifier.matchParentSize()
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                onBack?.let {
                    AppManagementIconAction(
                        iconRes = R.drawable.ic_arrow_back,
                        contentDescription = null,
                        tint = topBarContentColor,
                        onClick = it
                    )
                }
                Text(
                    text = title,
                    color = topBarContentColor,
                    // 2.3（bookshelf-refresh-and-title-fix）：19sp/SemiBold 孤例漂移归位
                    // titleLarge（20sp/Medium）基线，与 GlassTopAppBar/ConfigTopBar/View TitleBar 对齐
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
                actions.forEach { action ->
                    AppManagementTopAction(
                        action = action,
                        palette = palette,
                        contentColor = topBarContentColor
                    )
                }
            }
            if (onSearchChange != null) {
                AppManagementSearchField(
                    query = searchQuery.orEmpty(),
                    hint = searchHint.orEmpty(),
                    palette = palette,
                    onQueryChange = onSearchChange,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AppManagementTopAction(
    action: AppManagementAction,
    palette: AppManagementPalette,
    contentColor: Color
) {
    val menuActions = action.menuActions
    // followup F5：icon（ImageVector）优先渲染，fallback iconRes；
    // menuActions 场景走 AndroidView setImageResource 仅支持 iconRes
    if (action.icon != null && menuActions == null) {
        AppManagementVectorIconAction(
            icon = action.icon,
            contentDescription = action.text,
            tint = if (action.danger) palette.settings.danger else contentColor,
            onClick = action.onClick
        )
        return
    }
    val iconRes = action.iconRes ?: R.drawable.ic_more_vert
    if (menuActions != null) {
        AppManagementMoreActionButton(
            actionsProvider = menuActions,
            palette = palette,
            iconRes = iconRes,
            contentDescription = action.text,
            tint = if (action.danger) palette.settings.danger else contentColor
        )
    } else {
        AppManagementIconAction(
            iconRes = iconRes,
            contentDescription = action.text,
            tint = if (action.danger) palette.settings.danger else contentColor,
            onClick = action.onClick
        )
    }
}

@Composable
private fun AppManagementVectorIconAction(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AppManagementSearchField(
    query: String,
    hint: String,
    palette: AppManagementPalette,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = Color(palette.settings.row),
        contentColor = palette.settings.primaryText,
        cornerRadius = palette.miuix.actionRadius ?: 12.dp,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search),
                contentDescription = null,
                tint = palette.settings.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontFamily = palette.settings.bodyFontFamily
                ),
                cursorBrush = SolidColor(palette.settings.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isBlank()) {
                            Text(
                                text = hint,
                                color = palette.settings.secondaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                fontFamily = palette.settings.bodyFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                AppManagementIconAction(
                    iconRes = R.drawable.ic_baseline_close,
                    contentDescription = null,
                    tint = palette.settings.secondaryText,
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun AppManagementSelectionBottomBar(
    selectedCount: Int,
    totalCount: Int,
    palette: AppManagementPalette,
    actions: List<AppManagementAction>,
    onSelectAll: (() -> Unit)?,
    onInvertSelection: (() -> Unit)?
) {
    AnimatedVisibility(visible = selectedCount > 0) {
        val mainAction = actions.lastOrNull { it.danger } ?: actions.lastOrNull()
        val moreActions = if (mainAction == null) actions else actions.filterNot { it === mainAction }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(palette.settings.bottomBar))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.select_all_count, selectedCount, totalCount),
                color = palette.settings.bottomBarText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = onSelectAll != null) { onSelectAll?.invoke() }
                    .padding(vertical = 9.dp)
            )
            onInvertSelection?.let {
                CompactSelectionButton(
                    text = stringResource(R.string.revert_selection),
                    palette = palette,
                    onClick = it
                )
            }
            mainAction?.let { action ->
                Spacer(modifier = Modifier.width(6.dp))
                CompactSelectionButton(
                    text = action.text,
                    palette = palette,
                    danger = action.danger,
                    primary = action.primary,
                    onClick = action.onClick
                )
            }
            if (moreActions.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                SelectionMoreMenu(
                    actions = moreActions,
                    palette = palette
                )
            }
        }
    }
}

@Composable
private fun RowScope.CompactSelectionButton(
    text: String,
    palette: AppManagementPalette,
    danger: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    LegadoMiuixActionButton(
        text = text,
        palette = palette.miuix,
        onClick = onClick,
        primary = primary,
        danger = danger,
        minWidth = 72.dp,
        minHeight = 34.dp,
        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun SelectionMoreMenu(
    actions: List<AppManagementAction>,
    palette: AppManagementPalette
) {
    AppManagementMoreActionButton(
        actionsProvider = {
            actions.map { action ->
                AppManagementMenuAction(
                    text = action.text,
                    danger = action.danger,
                    onClick = action.onClick
                )
            }
        },
        palette = palette,
        contentDescription = stringResource(R.string.more_menu)
    )
}
