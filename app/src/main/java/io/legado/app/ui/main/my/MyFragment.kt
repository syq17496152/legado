package io.legado.app.ui.main.my

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.icons.Icons
import androidx.core.view.isVisible
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.service.WebService
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.data.appDb
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.ui.widget.components.MetricItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showHelp
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.LogUtils

class MyFragment() : BaseFragment(R.layout.fragment_my_config),
    MainFragmentInterface,
    SharedPreferences.OnSharedPreferenceChangeListener {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private val themeModeState = mutableStateOf("0")
    private val webServiceState = mutableStateOf(
        MyWebServiceUiState(checked = false, summary = "")
    )
    private val metricItemsState = mutableStateOf(emptyList<MetricItem>())
    private val sections by lazy(LazyThreadSafetyMode.NONE) { buildSettingsSections(requireContext()) }
    private val themeOptions by lazy(LazyThreadSafetyMode.NONE) { buildSettingsThemeOptions(requireContext()) }
    private val subSearchItems by lazy(LazyThreadSafetyMode.NONE) { buildSettingsSubSearchItems(requireContext()) }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        requireContext().putPrefBoolean(PreferKey.webService, WebService.isRun)
        initTopBar()
        installComposeContent()
        updateSettingsState()
    }

    // 顶栏组件归一（用户裁决）：与其他三大栏同一 MainTopBarView 组件（Mode.MY），替换 W2.4 的
    // GlassTopAppBar Compose 顶栏——单源尺寸对齐只统一了观感参数，组件与行为（壁纸层/标题交互/
    // 布局结构）仍双轨，现回同一组件彻底归一
    private fun initTopBar() {
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.setMode(MainTopBarView.Mode.MY)
        binding.topBar.setTitle(getString(R.string.my))
        binding.topBar.setSearchEntryVisible(false)
        binding.topBar.searchButton.setOnClickListener {
            SettingsSearchActivity.start(requireContext())
        }
        // topbar-icon-semantics-fix 3.4：帮助为一级问号图标（走 actionsBar 插槽统一染色与风格）
        binding.topBar.addActionButton(R.drawable.ic_help, R.string.help) { showHelp("appHelp") }
        binding.topBar.moreButton.isVisible = false
    }

    override fun observeLiveBus() {
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            updateWebServiceState()
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateSettingsState()
        // 用户反馈（2026-08-22）："我的"页头部四框统计信息（书架/书源/订阅源/累计阅读）隐藏
        // 代码保留（loadMetrics/buildMetricItems/MetricItem 完整存在），后期优化时恢复调用即可
        // loadMetrics()
    }

    override fun onPause() {
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.webService -> {
                if (requireContext().getPrefBoolean(PreferKey.webService)) {
                    WebService.start(requireContext())
                } else {
                    WebService.stop(requireContext())
                }
                updateWebServiceState()
            }

            PreferKey.themeMode -> {
                themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
            }

            "recordLog" -> LogUtils.upLevel()
        }
    }

    // my-compose-full W2.4 演进：顶栏已归还 MainTopBarView（与其他三大栏同组件），本宿主仅承载
    // 内容区 MySettingsScreen；路由逻辑零改动：onRowClick → handleSettingsRowClick
    private fun installComposeContent() {
        binding.composeHost.setContent {
            MySettingsScreen(
                sections = sections,
                subSearchItems = subSearchItems,
                searchQuery = "",
                metrics = metricItemsState.value,
                themeModeLabel = currentThemeModeLabel(
                    requireContext(),
                    themeOptions,
                    themeModeState.value
                ),
                webServiceState = webServiceState.value,
                onThemeModeClick = {
                    (activity as? AppCompatActivity)?.showThemeModeActions(
                        themeOptions,
                        themeModeState.value
                    ) { value ->
                        (activity as? AppCompatActivity)?.applyThemeMode(value) { themeModeState.value = it }
                    }
                },
                onWebServiceCheckedChange = {
                    (activity as? AppCompatActivity)?.setWebServiceEnabled(it) { webServiceState.value = it }
                },
                onWebServiceClick = {
                    (activity as? AppCompatActivity)?.handleWebServiceClick { webServiceState.value = it }
                },
                onRowClick = { key, searchTarget ->
                    activity?.handleSettingsRowClick(key, searchTarget)
                }
            )
        }
    }

    private fun updateSettingsState() {
        themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
        updateWebServiceState()
    }

    private fun updateWebServiceState() {
        webServiceState.value = requireContext().webServiceUiState()
    }

    private fun loadMetrics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val metrics = withContext(Dispatchers.IO) {
                runCatching {
                    val shelfCount = appDb.bookDao.flowShelfAll().first().size
                    val sourceCount = appDb.bookSourceDao.allCount()
                    val rssCount = appDb.rssSourceDao.size
                    val totalReadMs = appDb.readRecordDao.allTime
                    buildMetricItems(context, shelfCount, sourceCount, rssCount, totalReadMs)
                }.getOrDefault(emptyList())
            }
            metricItemsState.value = metrics
        }
    }

    private fun buildMetricItems(
        context: Context,
        shelfCount: Int,
        sourceCount: Int,
        rssCount: Int,
        totalReadMs: Long
    ): List<MetricItem> {
        val readHours = if (totalReadMs > 0) {
            "%.1f".format(totalReadMs / 3600_000f).trimEnd('0').trimEnd('.')
        } else {
            "0"
        }
        return listOf(
            MetricItem(
                label = context.getString(R.string.my_metric_shelf),
                value = shelfCount.toString(),
                icon = Icons.Filled.MenuBook
            ),
            MetricItem(
                label = context.getString(R.string.my_metric_book_source),
                value = sourceCount.toString(),
                icon = Icons.Filled.Collections
            ),
            MetricItem(
                label = context.getString(R.string.my_metric_rss_source),
                value = rssCount.toString(),
                icon = Icons.Filled.Subscriptions
            ),
            MetricItem(
                label = context.getString(R.string.my_metric_read_time),
                value = "$readHours${context.getString(R.string.unit_hour)}",
                icon = Icons.Filled.Schedule
            )
        )
    }
}