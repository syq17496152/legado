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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.titleTextColor

data class AppManagementAction(
    val text: String,
    @param:DrawableRes val iconRes: Int? = null,
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
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
    val topBarColor = if (AppConfig.immersiveManageBar) {
        context.backgroundColor
    } else {
        context.primaryColor
    }
    val topBarContentColor = Color(context.titleTextColor)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(topBarColor))
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
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
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

@Composable
private fun AppManagementTopAction(
    action: AppManagementAction,
    palette: AppManagementPalette,
    contentColor: Color
) {
    val iconRes = action.iconRes ?: R.drawable.ic_more_vert
    val menuActions = action.menuActions
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
                    fontSize = 14.sp,
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
                                fontSize = 14.sp,
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
                fontSize = 14.sp,
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
