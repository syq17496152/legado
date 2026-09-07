package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.viewbinding.ViewBinding
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 菜单动作项，由 [AppMenuSheet] / [AppDropdownMenu] 数据驱动渲染
 *
 * @property icon 图标（ImageVector 源；与 [iconRes] 双源二选一，icon 优先）
 * @property title 标题（调用方传 stringResource，遵守 §6.1 禁硬编码中文）
 * @property tint 图标与文字颜色，默认 null 走主题 onSurfaceVariant
 * @property checked 勾选态（复选类菜单），null 不显示勾选标记
 * @property onClick 点击回调
 */
data class MenuAction(
    val icon: ImageVector? = null,
    val title: String,
    val tint: androidx.compose.ui.graphics.Color? = null,
    val checked: Boolean? = null,
    val header: Boolean = false,
    // 顶栏分级语义（topbar-icon-semantics-fix AD-01）：true=固定显示为顶栏一级图标（不进溢出菜单），
    // false=溢出菜单（默认，向后兼容）。消费方：ConfigActivity.ConfigMenuActions（subpage-topbar-unify
    // AD-04 后原 ConfigTopBar 已消灭）；AppMenuSheet/AppDropdownMenu 忽略。
    // 注意：header=true 是溢出菜单内分组标签，不得与 alwaysShow=true 组合。
    val alwaysShow: Boolean = false,
    // W7.2（my-compose-full Delta 3→1）：drawable 资源源（原 MainTopBarView addActionButton 页迁移承载）
    @param:androidx.annotation.DrawableRes val iconRes: Int? = null,
    // W7.2：启用态（原 addActionButton isEnabled 切换承载，false 时图标不响应点击）
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * 长按条目弹出的底部操作面板（L1 层），复用 [AppModalBottomSheet]
 *
 * 数据驱动：传入 [actions] 列表逐项渲染，图标+文字行，触控高度 48dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMenuSheet(
    title: String? = null,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    AppModalBottomSheet(onDismiss = onDismiss, sheetState = sheetState) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
        }
        if (title != null && actions.isNotEmpty()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        actions.forEach { action ->
            if (action.header) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = 24.dp)
                ) {
                    MenuActionIcon(
                        action = action,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = action.tint ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
        content?.invoke(this)
    }
}

/**
 * 顶栏 action 分级渲染公共组件（subpage-topbar-unify 二期，三页统一顶栏共用）：
 * alwaysShow 且非 header 的一级图标直出，其余进溢出下拉菜单。
 * tint 不显式指定——由宿主顶栏组件（GlassTopAppBar）的 actionIconContentColor
 * （contrastOn 容器色）统一继承（subpage-topbar-unify 红队 R5）。
 */
@Composable
fun RowScope.TopBarActionRow(actions: List<MenuAction>) {
    val primaryActions = actions.filter { it.alwaysShow && !it.header }
    val overflowActions = actions.filter { !it.alwaysShow || it.header }
    var menuExpanded by remember { mutableStateOf(false) }
    primaryActions.forEach { action ->
        IconButton(onClick = action.onClick, enabled = action.enabled) {
            MenuActionIcon(action = action)
        }
    }
    if (overflowActions.isNotEmpty()) {
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            AppDropdownMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                actions = overflowActions
            )
        }
    }
}

/**
 * MenuAction 图标双源渲染（W7.2）：icon（ImageVector）优先，fallback iconRes（painterResource）。
 */
@Composable
fun MenuActionIcon(action: MenuAction, modifier: Modifier = Modifier) {
    when {
        action.icon != null -> Icon(
            imageVector = action.icon,
            contentDescription = action.title,
            tint = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        action.iconRes != null -> Icon(
            painter = androidx.compose.ui.res.painterResource(action.iconRes),
            contentDescription = action.title,
            tint = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }
}

/**
 * 运行时顶栏替换（subpage-topbar-unify 二期，共用布局页迁移模式）：
 * 共用布局（如 ActivityThemeManageBinding 14 页）全部迁移前 XML 的 MainTopBarView
 * 节点必须保留；已迁移页在运行时移除该节点并插入 ComposeView 承载 GlassTopAppBar。
 * 顶栏最终色走 resolvePageBarColorWithAlpha 单源（AD-01 v1.5）。
 */
fun ComponentActivity.installGlassTopBar(
    binding: ViewBinding,
    titleProvider: () -> String,
    actionsProvider: () -> List<MenuAction>,
    onBack: () -> Unit
) {
    val container = binding.root as? ViewGroup ?: return
    container.findViewById<View>(io.legado.app.R.id.title_bar)?.let { container.removeView(it) }
    val cv = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        // W7.2：通用 LayoutParams（容器 generateLayoutParams 自转换），兼容非 LinearLayout 根布局
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setContent {
            io.legado.app.ui.widget.compose.LegadoComposeTheme {
                GlassTopAppBar(
                    title = titleProvider(),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = onBack,
                    actions = { TopBarActionRow(actionsProvider()) }
                )
            }
        }
    }
    container.addView(cv, 0)
}
