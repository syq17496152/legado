package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.service.WebService
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.MainTopBarView
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Schedule
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
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
import io.legado.app.utils.applyStatusBarPadding
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

    // 顶栏对齐 Archive MainTopBarView（Mode.MY）：标题 + 搜索按钮 + 更多菜单；样式受顶栏/主题设置全量管理
    // header-search-unify：关闭 searchEntry 胶囊（仅保留 searchButton → 全屏设置搜索页），形态对齐订阅页
    private fun initTopBar() {
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.setMode(MainTopBarView.Mode.MY)
        binding.topBar.setTitle(getString(R.string.my))
        binding.topBar.setSearchEntryVisible(false)
        binding.topBar.searchButton.setOnClickListener {
            SettingsSearchActivity.start(requireContext())
        }
        // topbar-icon-semantics-fix 3.4：帮助恢复一级问号图标（原版 main_my.xml menu_help always；
        // 此前 moreButton 点击直接弹帮助，视觉语义不符）。原版 main_my.xml 仅 help 一项，
        // 恢复一级后溢出无剩余项，moreButton 隐藏（走 addActionButton/actionsBar 插槽统一染色与风格适配）
        binding.topBar.addActionButton(R.drawable.ic_help, R.string.help) { showHelp("appHelp") }
        binding.topBar.moreButton.isVisible = false
    }

    private fun installComposeContent() {
        binding.preFragment.removeAllViews()
        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
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
        binding.preFragment.addView(composeView)
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