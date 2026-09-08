package io.legado.app.ui.widget.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.ThemeSync
import io.legado.app.ui.theme.subtitleLarge
import io.legado.app.ui.theme.subtitleLargeX
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.contrastOn

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
    // 6.6 委托收官：内容色口径保留原 AppManagementTopBar 公式（contrastOn(resolvePageBarColorWithAlpha)，
    // 与 GlassTopAppBar 内部同源），供管理族 Action 图标 tint 使用
    val topBarContentColor = remember(ThemeSync.version) {
        contrastOn(
            Color(
                TopBarConfig.resolvePageBarColorWithAlpha(
                    rootContext,
                    TopBarConfig.currentConfig(rootContext, AppConfig.isNightTheme)
                )
            )
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (bgAlpha > 0f) Modifier.background(Color(rootContext.backgroundColor).copy(alpha = bgAlpha))
                else Modifier
            )
    ) {
        // 6.5/6.6（Delta 3→1 归一）：管理族顶栏委托扩展版 GlassTopAppBar——
        // 高度槽 48dp+双行搜索槽+statusBars 内嵌+标题字体槽，渲染路径与 Glass 族单源
        GlassTopAppBar(
            title = title,
            navIcon = if (onBack != null) Icons.AutoMirrored.Filled.ArrowBack else null,
            onNavClick = onBack,
            actions = {
                topActions.forEach { action ->
                    AppManagementTopAction(
                        action = action,
                        palette = palette,
                        contentColor = topBarContentColor
                    )
                }
            },
            barHeight = 48.dp,
            titleFontFamily = palette.settings.titleFontFamily,
            secondRow = if (onSearchChange != null) {
                {
                    AppManagementSearchField(
                        query = searchQuery.orEmpty(),
                        hint = searchHint.orEmpty(),
                        palette = palette,
                        onQueryChange = onSearchChange,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                }
            } else {
                null
            }
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
    // bugfix-0908f 尺寸单源：容器/图标经 TopBarConfig 唯一口径（与主 Tab 同源，regular 36/20、default 34/18）
    val context = LocalContext.current
    val container = TopBarConfig.actionContainerSize(context)
    val iconSize = TopBarConfig.actionIconSize(context)
    IconButton(
        onClick = onClick,
        modifier = modifier.size(container.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize.dp)
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
    // bugfix-0908f 尺寸单源：搜索框内图标经 TopBarConfig 唯一口径，禁止写死
    val context = LocalContext.current
    val iconSize = TopBarConfig.actionIconSize(context)
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
                modifier = Modifier.size(iconSize.dp)
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
                // 清除按钮尺寸走 AppManagementIconAction 内部单源口径，不覆盖
                AppManagementIconAction(
                    iconRes = R.drawable.ic_baseline_close,
                    contentDescription = null,
                    tint = palette.settings.secondaryText,
                    onClick = { onQueryChange("") }
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
