package io.legado.app.ui.config

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout.LayoutParams as FrameLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.contrastOn
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import androidx.viewbinding.ViewBinding
import java.io.File

/**
 * 设置宿主页（D 类 7.11x：顶栏回退 Archive）。
 * 顶栏改用专有 [ConfigTopBar]（Compose）承载标题 + 三点菜单（AppDropdownMenu），
 * Fragment 经 setConfigMenuActions 上报菜单动作。
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        titleComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        titleComposeView.setContent {
            ConfigTopBar(
                title = titleText,
                onBack = ::supportFinishAfterTransition,
                actions = menuActions
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

@Composable
private fun ConfigTopBar(
    title: String,
    onBack: () -> Unit,
    actions: List<MenuAction>
) {
    val palette = rememberAppSettingPalette()
    val context = LocalContext.current
    // H6: 顶栏背景随"顶栏管理"（TopBarConfig）——背景色 + 不透明度 + 壁纸；主题 token 变化时随 palette 重建
    val config = remember(palette.themeSignature) {
        TopBarConfig.currentConfig(context, AppConfig.isNightTheme)
    }
    val wallpaperFile = remember(config.wallpaperPath, palette.themeSignature) {
        config.wallpaperPath?.takeIf { it.isNotBlank() }
            ?.let { TopBarConfig.currentWallpaperFile(context, config.isNightMode) }
    }
    // 简化说明: 壁纸全幅显示（crop 裁切对齐归 H13 Glass 统一组件，此处按 MainTopBarView 视觉近似）
    val wallpaper = remember(wallpaperFile) { wallpaperFile?.let(::decodeTopBarBitmap) }
    val bgColor = remember(config) {
        Color(TopBarConfig.withOpacity(TopBarConfig.resolveBackgroundColor(config), config.wallpaperAlpha))
    }
    val radius = TopBarConfig.cornerRadius(context, config)
    var menuExpanded by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.bodyFontFamily)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // H6 修正（2026-08-28）：clip→background→statusBarsPadding 顺序——背景铺满含状态栏
                // 区域（修"头部不占满"）且被圆角裁切；此前 statusBarsPadding 在 background 前导致
                // 状态栏透明露出窗口底色（对照 AppManagementTopBar 正确顺序）
                .clip(RoundedCornerShape(radius))
                .background(bgColor)
                .statusBarsPadding()
                .height(56.dp)
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
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // topbar-icon-semantics-fix AD-02: 分级渲染——alwaysShow 一级图标直出，其余进溢出下拉
                val primaryActions = actions.filter { it.alwaysShow && !it.header }
                val overflowActions = actions.filter { !it.alwaysShow || it.header }
                // AD-07: 一级图标 tint 取容器背景对比度推导色（随顶栏包背景/夜间自动适配），禁硬编码
                val primaryTint = contrastOn(bgColor)
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = null,
                        tint = palette.primaryText,
                        // 2.4：action 图标绘制尺寸统一 20dp（bookshelf-refresh-and-title-fix R4）
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = title,
                    color = palette.primaryText,
                    fontFamily = palette.titleFontFamily,
                    // 2.2（bookshelf-refresh-and-title-fix）：去 SemiBold 覆写，回归 titleLarge（20sp/Medium）基线
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                primaryActions.forEach { action ->
                    IconButton(onClick = action.onClick) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.title,
                            tint = primaryTint,
                            // 2.4：图标 20dp 档
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
                                tint = palette.primaryText,
                                // 2.4：图标 20dp 档
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        AppDropdownMenu(
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            actions = overflowActions
                        )
                    }
                } else if (primaryActions.isEmpty()) {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    }
}

/** 顶栏壁纸有界解码（防大图 OOM）；复用 MainTopBarView 渲染近似。 */
private fun decodeTopBarBitmap(file: File, maxDim: Int = 2048): Bitmap? {
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
