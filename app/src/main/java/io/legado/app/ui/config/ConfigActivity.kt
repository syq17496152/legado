package io.legado.app.ui.config

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout.LayoutParams as FrameLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import androidx.viewbinding.ViewBinding

private const val CONFIG_MENU_HOST_WIDTH_DP = 128

/**
 * 设置宿主页（D 类 7.11x：顶栏回退 Archive）。
 * 顶栏改用专有 [ConfigTopBar]（Compose）+ 隐藏 Toolbar 作为 supportActionBar 承载
 * Fragment 的 MenuProvider 菜单（`activity.addMenuProvider`），同 Archive 接线。
 * 主题架构 v2：宿主页豁免主题重建（改色经 ThemeSync 即时换肤），RECREATE 由 BaseActivity 处理。
 */
class ConfigActivity : VMBaseActivity<ViewBinding, ConfigViewModel>() {

    private lateinit var titleComposeView: ComposeView
    private lateinit var menuToolbar: Toolbar
    private var titleText by mutableStateOf("")

    override val binding: ViewBinding by lazy {
        titleText = getString(R.string.setting)
        titleComposeView = ComposeView(this)
        menuToolbar = Toolbar(this).apply {
            title = ""
            subtitle = ""
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContentInsetsAbsolute(0, 0)
            contentInsetStartWithNavigation = 0
            contentInsetEndWithActions = 0
        }
        val topBarHost = FrameLayout(this).apply {
            addView(
                titleComposeView,
                FrameLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                menuToolbar,
                FrameLayoutParams(
                    CONFIG_MENU_HOST_WIDTH_DP.dpToPx(),
                    56.dpToPx(),
                    Gravity.END or Gravity.BOTTOM
                )
            )
            setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val top = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                (menuToolbar.layoutParams as? FrameLayoutParams)?.let { params ->
                    if (params.topMargin != top) {
                        params.topMargin = top
                        menuToolbar.layoutParams = params
                    }
                }
                windowInsets
            }
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
        setSupportActionBar(menuToolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        titleComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        titleComposeView.setContent {
            ConfigTopBar(
                title = titleText,
                onBack = ::supportFinishAfterTransition
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
    onBack: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.bodyFontFamily)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = palette.primaryText
                )
            }
            Text(
                text = title,
                color = palette.primaryText,
                fontFamily = palette.titleFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(CONFIG_MENU_HOST_WIDTH_DP.dp))
        }
    }
}