package io.legado.app.ui.rss.article

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ActivityRssArtivlesBinding
import io.legado.app.help.source.getSearchUrl
import io.legado.app.help.source.sortUrls
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.shouldHideSoftInput
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D4 §5 classic 分类宿主（design-b3-d4-flagship）：VMBaseActivity 壳保留
 * （Intent 处理/onNewIntent/registerForActivityResult），ViewPager+FragmentStatePagerAdapter
 * 家族退役为 [RssSortScreen]（GlassTopAppBar+SortTabBar+HorizontalPager）；
 * 每页 RssArticlesViewModel 按 key 隔离（§5.4 注意①）。
 */
class RssSortActivity : VMBaseActivity<ActivityRssArtivlesBinding, RssSortViewModel>(),
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityRssArtivlesBinding::inflate)
    override val viewModel by viewModels<RssSortViewModel>()

    // Compose 桥接状态（原 sortList/fragmentMap/ViewPager 家族退役，§5）
    private var sorts by mutableStateOf<List<Pair<String, String>>>(emptyList())
    /** 翻页菜单项标题（null 时不显示，由 updatePageMenu 驱动，§5.2） */
    private var pageMenuTitle by mutableStateOf<String?>(null)
    private var currentSortIndex = 0
    /** 翻页选择后目标页回顶请求（first=目标页索引，second=序号防同页重触发，§3.3） */
    private var pageScrollTopRequest by mutableStateOf(-1 to 0)
    private var cachedSortUrls: List<Pair<String, String>>? = null

    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.initData(intent) {
                cachedSortUrls = null
                upFragments()
            }
        }
    }

    // 修复：登录返回后刷新当前列表（之前 startActivity 无回调导致列表不刷新）
    private val loginResult = registerForActivityResult(
        StartActivityContract(SourceLoginActivity::class.java)
    ) {
        refreshCurrentPage()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setContent {
            LegadoTheme {
                RssSortScreen(
                    sortViewModel = viewModel,
                    sorts = sorts,
                    pageMenuTitle = pageMenuTitle,
                    pageScrollTopRequest = pageScrollTopRequest,
                    onBack = { finish() },
                    onSearch = ::startSearch,
                    onPagePicker = ::showPagePicker,
                    onLogin = ::startLogin,
                    onRefreshSorts = ::refreshSorts,
                    onSetVariable = ::setSourceVariable,
                    onEditSource = ::editSource,
                    onReadRecord = ::showReadRecord,
                    onClearArticles = ::clearArticles,
                    onCurrentPageChanged = { currentSortIndex = it },
                    onPageChanged = ::updatePageMenu,
                    onOpenArticle = ::openArticle,
                )
            }
        }
        viewModel.initData(intent) {
            upFragments()
        }
        onBackPressedDispatcher.addCallback(this) { //监听返回
            if (viewModel.searchKey != null) {
                // 退出搜索
                viewModel.searchKey = null
                upFragments()
                return@addCallback
            }
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 更新当前intent
        // 重新初始化数据，复用时重建
        viewModel.initData(intent) {
            upFragments()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it.shouldHideSoftInput(ev)) {
                    it.hideSoftInput()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun updatePageMenu(page: Int, visible: Boolean) {
        pageMenuTitle = if (visible) getString(R.string.menu_page, page) else null
    }

    // ===== 分类解析（原 upFragments 逻辑平移，写入 Compose state）=====

    private fun upFragments() {
        lifecycleScope.launch {
            val source = viewModel.rssSource ?: return@launch
            if (viewModel.searchKey != null) {
                // L452 硬编码"搜索"文案 → string 资源（§5.4，R.string.search en+zh 双语既有 key）
                val name = getString(R.string.search)
                var url = source.searchUrl ?: return@launch
                // 如果 searchUrl 是 JS，在独立线程预执行避免协程死锁
                if (url.startsWith("<js>", true) || url.startsWith("@js:", true)) {
                    url = source.getSearchUrl(viewModel.searchKey!!) ?: url
                }
                sorts = listOf(Pair(name, url))
                return@launch
            }
            viewModel.sortUrl?.takeIf { it.isNotBlank() }?.let { url ->
                sorts = try {
                    if (url.isJsonObject()) {
                        GSONStrict.fromJsonObject<Map<String, String>>(url)
                            .getOrThrow()
                            .map { Pair(it.key, it.value) }
                    } else {
                        listOf(Pair("", url))
                    }
                } catch (_: Exception) {
                    listOf(Pair("", url))
                }
                return@launch
            }
            if (cachedSortUrls == null) {
                cachedSortUrls = source.sortUrls()
            }
            cachedSortUrls?.let { urls ->
                sorts = urls
            }
        }
    }

    // ===== 顶栏菜单/弹框上行（原 buildMenuActions 平移，Activity 侧执行）=====

    private fun startSearch(query: String) {
        viewModel.rssSource?.let { source ->
            start(this, null, source.sourceUrl, query)
        }
    }

    private fun startLogin() {
        loginResult.launch {
            putExtra("type", "rssSource")
            putExtra("key", viewModel.rssSource?.sourceUrl)
        }
    }

    private fun refreshSorts() {
        cachedSortUrls = null
        viewModel.clearSortCache { upFragments() }
    }

    private fun editSource() {
        viewModel.rssSource?.let {
            editSourceResult.launch {
                putExtra("sourceUrl", it.sourceUrl)
            }
        }
    }

    private fun showReadRecord() {
        showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl))
    }

    private fun clearArticles() {
        viewModel.url?.let {
            viewModel.clearArticles()
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.rssSource
            if (source == null) {
                toastOnUi("源不存在")
                return@launch
            }
            val comment =
                source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
            val variable = withContext(Dispatchers.IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    // ===== 当前页 VM 定位（替代原 currentArticlesFragment，§5.4）=====

    private fun pageViewModel(sortName: String): RssArticlesViewModel =
        ViewModelProvider(this, defaultViewModelProviderFactory)
            .get("rss_articles_$sortName", RssArticlesViewModel::class.java)

    /** 登录返回刷新当前 Tab 页（原 currentArticlesFragment?.refreshAfterLogin 等价：page 1 重载） */
    private fun refreshCurrentPage() {
        val sortName = sorts.getOrNull(currentSortIndex)?.first ?: return
        viewModel.rssSource?.let { source ->
            pageViewModel(sortName).loadArticles(source, 1)
        }
    }

    /** 翻页菜单（原 fragment showPagePicker 平移：选页后重载 + 回顶，§3.3） */
    private fun showPagePicker() {
        val source = viewModel.rssSource ?: return
        if (source.ruleNextPage.isNullOrEmpty()) return
        val sortName = sorts.getOrNull(currentSortIndex)?.first ?: return
        val pageVm = pageViewModel(sortName)
        val currentPage = pageVm.page
        NumberPickerDialog(this)
            .setTitle(getString(R.string.change_page))
            .setMinValue(1)
            .setMaxValue(999)
            .setValue(currentPage)
            .show { targetPage ->
                if (targetPage != currentPage) {
                    pageVm.loadArticles(source, targetPage)
                    pageScrollTopRequest = currentSortIndex to (pageScrollTopRequest.second + 1)
                }
            }
    }

    /** 文章点击上行（§3.3）：classic 页无 Fragment，走 ReadRss activity 重载 */
    private fun openArticle(pageVm: RssArticlesViewModel, rssArticle: RssArticle, articles: List<RssArticle>) {
        // 传递文章列表给播放器，支持上下滑动切换文章（video-article-swipe-switch spec）
        ReadRss.readRss(
            this, rssArticle, articles,
            sortName = pageVm.sortName,
            sortUrl = pageVm.sortUrl,
            nextPageUrl = pageVm.nextPageUrl,
            page = pageVm.page
        )
    }

    companion object {
        fun start(context: Context, sortUrl: String?, sourceUrl: String, key: String? = null) {
            context.startActivity<RssSortActivity> {
                putExtra("sortUrl", sortUrl)
                putExtra("sourceUrl", sourceUrl)
                putExtra("key", key)
            }
        }
    }

}
