package io.legado.app.ui.config

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityConfigBinding
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding

class ConfigActivity : VMBaseActivity<ActivityConfigBinding, ConfigViewModel>() {

    override val binding by viewBinding(ActivityConfigBinding::inflate)
    override val viewModel by viewModels<ConfigViewModel>()

    // L-E1/L-E2 S2 改造：Compose 顶栏标题与菜单状态（Fragment 通过 setTopBarMenu 注册菜单）
    // 注意：不能在属性初始化处调用 getString()（构造期 Context 未 attach 会 NPE），需在 onActivityCreated 中赋值
    private var composeTitle by mutableStateOf("")
    private var menuActions by mutableStateOf(listOf<MenuAction>())
    private var menuExpanded by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        composeTitle = getString(R.string.setting)
        initComposeTopBar()
        when (val configTag = intent.getStringExtra("configTag")) {
            ConfigTag.OTHER_CONFIG -> replaceFragment<OtherConfigFragment>(configTag)
            ConfigTag.THEME_CONFIG -> replaceFragment<ThemeConfigFragment>(configTag)
            ConfigTag.BACKUP_CONFIG -> replaceFragment<BackupConfigFragment>(configTag)
            ConfigTag.COVER_CONFIG -> replaceFragment<CoverConfigFragment>(configTag)
            ConfigTag.WELCOME_CONFIG -> replaceFragment<WelcomeConfigFragment>(configTag)
            ConfigTag.PRECISE_MANAGE -> replaceFragment<PreciseManageFragment>(configTag)
            else -> finish()
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        composeTitle = getString(resId)
    }

    /**
     * Fragment 注册顶栏下拉菜单动作（迁移自 MenuProvider 的 onCreateMenu/onMenuItemSelected）。
     * actions 为空时顶栏不显示 MoreVert 菜单按钮。
     */
    fun setTopBarMenu(actions: List<MenuAction>) {
        menuActions = actions
    }

    /**
     * Compose 顶栏（L-E1/L-E2 S2 改造）：GlassTopAppBar + 返回按钮 + 可选 MoreVert 下拉菜单
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = composeTitle,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        if (menuActions.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = getString(R.string.more)
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = menuActions
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    inline fun <reified T : Fragment> replaceFragment(configTag: String) {
        intent.putExtra("configTag", configTag)
        @Suppress("DEPRECATION")
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: T::class.java.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.configFrameLayout, configFragment, configTag)
            .commit()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
    }

}
