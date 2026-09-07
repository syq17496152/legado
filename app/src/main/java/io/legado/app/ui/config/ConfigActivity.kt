package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout.LayoutParams as FrameLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import androidx.viewbinding.ViewBinding

/**
 * 设置宿主页（D 类 7.11x：顶栏回退 Archive）。
 * 顶栏统一走 [GlassTopAppBar]（subpage-topbar-unify AD-04：ConfigTopBar 已消灭，
 * Compose 页唯一通用顶栏），Fragment 经 setConfigMenuActions 上报菜单动作，
 * 一级图标直出 + 其余进溢出下拉（MenuAction 适配见 [ConfigMenuActions]）。
 * 主题架构 v2：宿主页豁免主题重建（改色经 ThemeSync 即时换肤），RECREATE 由 BaseActivity 处理。
 */
class ConfigActivity : VMBaseActivity<ViewBinding, ConfigViewModel>() {

    private lateinit var titleComposeView: ComposeView
    private var titleText by mutableStateOf("")
    // H6: 内部属性用 menuActions（delegate 自动 setter 与下方 setConfigMenuActions 方法 JVM 同名为 clash）
    var menuActions by mutableStateOf<List<MenuAction>>(emptyList())

    fun setConfigMenuActions(actions: List<MenuAction>) {
        if (menuActions != actions) {
            menuActions = actions
        }
    }

    override val binding: ViewBinding by lazy {
        titleText = getString(R.string.setting)
        titleComposeView = ComposeView(this)
        val topBarHost = FrameLayout(this).apply {
            addView(
                titleComposeView,
                FrameLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                topBarHost,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                FrameLayout(this@ConfigActivity).apply {
                    id = R.id.configFrameLayout
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        object : ViewBinding {
            override fun getRoot(): View = root
        }
    }
    override val viewModel by viewModels<ConfigViewModel>()

    // 主题架构 v2：设置宿主页豁免主题重建（改色经 ThemeSync 即时换肤，避免活预览页闪屏）
    override val recreateOnThemeChange: Boolean
        get() = false

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        titleComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        titleComposeView.setContent {
            GlassTopAppBar(
                title = titleText,
                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavClick = ::supportFinishAfterTransition,
                actions = { ConfigMenuActions(actions = menuActions) }
            )
        }
        when (val configTag = intent.getStringExtra("configTag")) {
            ConfigTag.OTHER_CONFIG -> replaceFragment(configTag, OtherConfigFragment::class.java)
            ConfigTag.THEME_CONFIG -> replaceFragment(configTag, ThemeConfigFragment::class.java)
            ConfigTag.BACKUP_CONFIG -> replaceFragment(configTag, BackupConfigFragment::class.java)
            ConfigTag.AI_CONFIG -> replaceFragment(configTag, AiConfigFragment::class.java)
            ConfigTag.COVER_CONFIG -> replaceFragment(configTag, CoverConfigFragment::class.java)
            ConfigTag.WELCOME_CONFIG -> replaceFragment(configTag, WelcomeConfigFragment::class.java)
            ConfigTag.PRECISE_MANAGE -> replaceFragment(configTag, PreciseManageFragment::class.java)
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG ->
                replaceFragment(configTag, DiscoverySubscriptionConfigFragment::class.java)
            ConfigTag.DISCOVERY_CONFIG -> replaceFragment(configTag, DiscoveryConfigFragment::class.java)
            ConfigTag.SUBSCRIPTION_CONFIG -> replaceFragment(configTag, SubscriptionConfigFragment::class.java)
            // video-player-dual-layout：视频播放器全局设置页（路线 B 普通 Fragment）
            ConfigTag.VIDEO_PLAYER -> replaceFragment(configTag, VideoPlayerConfigFragment::class.java)
            else -> finish()
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        titleText = getString(resId)
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        titleText = title?.toString().orEmpty()
    }

    fun <T : Fragment> replaceFragment(configTag: String, fragmentClass: Class<T>) {
        intent.putExtra("configTag", configTag)
        // 切换目标页时清空上一页上报的菜单，由新页 onViewCreated 上报自己的菜单
        menuActions = emptyList()
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: fragmentClass.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.configFrameLayout, configFragment, configTag)
            .commit()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        // RECREATE 订阅上移 BaseActivity（主题架构 v2），此处不再重复订阅
    }

}

/**
 * MenuAction 分级渲染适配（topbar-icon-semantics-fix AD-02 语义平移）：
 * alwaysShow 且非 header 的一级图标直出，其余进溢出下拉菜单。
 * 图标 tint 不显式指定——由 GlassTopAppBar 的 actionIconContentColor（contrastOn 容器色）统一继承，
 * 修复原 ConfigTopBar 混用 palette.primaryText 的半改状态（subpage-topbar-unify 红队 R5）。
 */
@Composable
private fun RowScope.ConfigMenuActions(actions: List<MenuAction>) {
    val primaryActions = actions.filter { it.alwaysShow && !it.header }
    val overflowActions = actions.filter { !it.alwaysShow || it.header }
    var menuExpanded by remember { mutableStateOf(false) }
    primaryActions.forEach { action ->
        IconButton(onClick = action.onClick) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                // 2.4：action 图标绘制尺寸统一 20dp（bookshelf-refresh-and-title-fix R4）
                modifier = Modifier.size(20.dp)
            )
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
