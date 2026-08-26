package io.legado.app.ui.main.my

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivitySettingsSearchBinding
import io.legado.app.service.WebService
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 我的页全屏设置搜索页（header-search-unify 新增）
 *
 * 交互完全对齐订阅页搜索页范式：头部仅搜索按钮 → 弹开新页面搜索。
 * 主题零破坏（AD-04）：全部 Compose 包 [LegadoTheme]；列表复用 [MySettingsScreen]
 * （内部 palette 全量走 token）；顶栏/搜索框复用 GlassTopAppBar/SettingsSearchBar；
 * 数据构建 + 行点击路由 + 主题模式/Web 服务交互复用 MySettingsData 共享顶层函数/扩展（AD-03）。
 * 状态刷新（AD-05）：PrefKey.themeMode/webService 监听 + EventBus.WEB_SERVICE，与 MyFragment 恒等。
 */
class SettingsSearchActivity : BaseActivity<ActivitySettingsSearchBinding>(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    override val binding by viewBinding(ActivitySettingsSearchBinding::inflate)

    private var searchQuery by mutableStateOf("")
    private val themeModeState = mutableStateOf("0")
    private val webServiceState = mutableStateOf(MyWebServiceUiState(checked = false, summary = ""))
    private val sections by lazy { buildSettingsSections(this) }
    private val themeOptions by lazy { buildSettingsThemeOptions(this) }
    private val subSearchItems by lazy { buildSettingsSubSearchItems(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        themeModeState.value = getPrefString(PreferKey.themeMode, "0") ?: "0"
        updateWebServiceState()
        binding.composeRoot.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.search),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() }
                    )
                    SettingsSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = getString(R.string.settings_search)
                    )
                    MySettingsScreen(
                        sections = sections,
                        subSearchItems = subSearchItems,
                        searchQuery = searchQuery,
                        themeModeLabel = currentThemeModeLabel(this@SettingsSearchActivity, themeOptions, themeModeState.value),
                        webServiceState = webServiceState.value,
                        onThemeModeClick = {
                            showThemeModeActions(themeOptions, themeModeState.value) { value ->
                                applyThemeMode(value) { themeModeState.value = it }
                            }
                        },
                        onWebServiceCheckedChange = { setWebServiceEnabled(it) { webServiceState.value = it } },
                        onWebServiceClick = { handleWebServiceClick { webServiceState.value = it } },
                        onRowClick = { key, searchTarget -> handleSettingsRowClick(key, searchTarget) }
                    )
                }
            }
        }
    }

    override fun observeLiveBus() {
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            updateWebServiceState()
        }
    }

    override fun onResume() {
        super.onResume()
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.webService -> {
                if (getPrefBoolean(PreferKey.webService, false)) {
                    WebService.start(this)
                } else {
                    WebService.stop(this)
                }
                updateWebServiceState()
            }

            PreferKey.themeMode -> {
                themeModeState.value = getPrefString(PreferKey.themeMode, "0") ?: "0"
            }
        }
    }

    private fun updateWebServiceState() {
        webServiceState.value = webServiceUiState()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity<SettingsSearchActivity>()
        }
    }
}