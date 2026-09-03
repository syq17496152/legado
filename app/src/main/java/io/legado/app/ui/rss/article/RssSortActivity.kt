@file:Suppress("DEPRECATION")

package io.legado.app.ui.rss.article

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pageview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssArtivlesBinding
import io.legado.app.help.source.getSearchUrl
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.viewpager.widget.ViewPager
import io.legado.app.utils.startActivity

class RssSortActivity : VMBaseActivity<ActivityRssArtivlesBinding, RssSortViewModel>(),
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityRssArtivlesBinding::inflate)
    override val viewModel by viewModels<RssSortViewModel>()
    private val adapter by lazy { TabFragmentPageAdapter() }
    private var sortUrls: List<Pair<String, String>>? = null
    private val sortList = mutableListOf<Pair<String, String>>()
    private val fragmentMap = hashMapOf<String, Fragment>()
    private val orientation by lazy { resources.configuration.orientation }

    // L-D4 顶栏 Compose 状态
    private var composeTitle by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var searchDialogVisible by mutableStateOf(false)
    /** 翻页菜单项标题（null 时不显示，由 updatePageMenu 驱动） */
    private var pageMenuTitle by mutableStateOf<String?>(null)
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.initData(intent) {
                sortUrls = null
                upFragments()
            }
        }
    }

    // 修复：登录返回后刷新当前列表（之前 startActivity 无回调导致列表不刷新）
    private val loginResult = registerForActivityResult(
        StartActivityContract(SourceLoginActivity::class.java)
    ) {
        currentArticlesFragment?.refreshAfterLogin()
    }

    // 添加类属性
    private val tabRows = mutableListOf<LinearLayout>()
    var maxTagsPerRow = 10 // 每行尽量容纳10个标签,横屏20
    private val tabScrollViews = mutableListOf<HorizontalScrollView>() // 添加滚动视图列表

    private fun setupMultiLineTabs() {
        val tabsContainer = binding.tabsContainer
        tabsContainer.removeAllViews()
        tabRows.clear()
        tabScrollViews.clear()
        if (sortList.isEmpty()) {
            tabsContainer.gone()
            return
        }
        // 动态计算每行标签数量,最多3行
        var rowCount = when {
            sortList.size <= 10 -> 1
            sortList.size <= 20 -> 2
            else -> 3
        }
        if (rowCount > 1 && orientation == Configuration.ORIENTATION_LANDSCAPE) rowCount-- //横屏最多2行
        maxTagsPerRow = (sortList.size + rowCount - 1) / rowCount
        sortList.chunked(maxTagsPerRow).forEachIndexed { rowIndex, rowItems ->
            // 创建横向滚动容器
            val scrollView = HorizontalScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 6.dpToPx()
                }
                tabScrollViews.add(this)
            }
            // 创建行容器
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            // 添加标签到行
            rowItems.forEachIndexed { indexInRow, sort ->
                val globalIndex = rowIndex * maxTagsPerRow + indexInRow
                val tabView = createTabView(sort.first, globalIndex)
                rowLayout.addView(tabView)
            }
            scrollView.addView(rowLayout)
            tabsContainer.addView(scrollView)
            tabRows.add(rowLayout)
        }
        // 初始选中状态
        updateTabSelection(binding.viewPager.currentItem)
    }

    private fun createTabView(title: String, position: Int): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 14f
            background = createTabBackground(accentColor, context)
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            tag = position
            setTextColor(context.getCompatColor( R.color.primaryText))
            // 宽度自适应内容
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 6.dpToPx()
            }
            setOnClickListener {
                setTextColor(context.getCompatColor(R.color.secondaryText)) //点击变色
                binding.viewPager.currentItem = position
                updateTabSelection(position)
            }
        }
    }

    private fun createTabBackground(accentColor: Int, context: Context): Drawable {
        val radius = 16f.dpToPx()
        val strokeWidth = 1f.dpToPx()

        val selectedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setStroke(strokeWidth.toInt(), accentColor)
        }

        val defaultDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selectedDrawable)
            addState(intArrayOf(), defaultDrawable)
        }
    }

    //更新选中状态
    private fun updateTabSelection(position: Int) {
        if (!isDestroyed && !isFinishing) {
            tabRows.forEachIndexed { rowIndex, row ->
                for (i in 0 until row.childCount) {
                    val tabIndex = rowIndex * maxTagsPerRow + i
                    val tabView = row.getChildAt(i) as? TextView
                    tabView?.isSelected = tabIndex == position
                }
            }
            // 确保选中标签在视图内
            ensureTabVisible(position)
        }
    }

    private fun ensureTabVisible(position: Int) {
        if (position < 0 || position >= sortList.size) return
        val rowIndex = position / maxTagsPerRow
        if (rowIndex >= tabScrollViews.size) return
        val scrollView = tabScrollViews[rowIndex]
        val rowLayout = tabRows[rowIndex]
        val indexInRow = position % maxTagsPerRow
        if (indexInRow >= rowLayout.childCount) return

        val tabView = rowLayout.getChildAt(indexInRow)
        scrollView.post {
            val tabLeft = tabView.left
            val tabRight = tabView.right
            val scrollViewWidth = scrollView.width
            val padding = 12.dpToPx()
            when {
                tabLeft - padding < scrollView.scrollX ->
                    scrollView.smoothScrollTo(tabLeft - padding, 0)
                tabRight + padding > scrollView.scrollX + scrollViewWidth ->
                    scrollView.smoothScrollTo(tabRight - scrollViewWidth + padding, 0)
            }
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        binding.viewPager.adapter = adapter
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })
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

    // L-D4 顶栏 Compose 化：GlassTopAppBar + 更多菜单 AppDropdownMenu（搜索/翻页/登录/刷新分类等全量下沉）
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = composeTitle,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        if (viewModel.rssSource?.searchUrl.isNullOrBlank().not()) {
                            IconButton(onClick = { searchDialogVisible = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = getString(R.string.action_search)
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = null
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildMenuActions()
                            )
                        }
                    }
                )
                if (searchDialogVisible) {
                    AppEditDialog(
                        title = getString(R.string.action_search),
                        fields = listOf(
                            EditField(
                                label = getString(R.string.action_search),
                                singleLine = true
                            )
                        ),
                        confirmText = getString(R.string.ok),
                        cancelText = getString(R.string.cancel),
                        onConfirm = { values ->
                            searchDialogVisible = false
                            val query = values.firstOrNull().orEmpty()
                            if (query.isNotBlank()) {
                                viewModel.rssSource?.let { source ->
                                    start(this@RssSortActivity, null, source.sourceUrl, query)
                                }
                            }
                        },
                        onDismiss = { searchDialogVisible = false }
                    )
                }
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        pageMenuTitle?.let { title ->
            actions += MenuAction(
                Icons.Filled.Pageview,
                title,
                onClick = { currentArticlesFragment?.showPagePicker() }
            )
        }
        if (viewModel.rssSource?.loginUrl.isNullOrBlank().not()) {
            actions += MenuAction(
                Icons.Filled.Login,
                getString(R.string.login),
                onClick = {
                    loginResult.launch {
                        putExtra("type", "rssSource")
                        putExtra("key", viewModel.rssSource?.sourceUrl)
                    }
                }
            )
        }
        actions += MenuAction(
            Icons.Filled.Refresh,
            getString(R.string.refresh_sort),
            onClick = {
                sortUrls = null
                viewModel.clearSortCache { upFragments() }
            }
        )
        actions += MenuAction(
            Icons.Filled.Tune,
            getString(R.string.set_source_variable),
            onClick = { setSourceVariable() }
        )
        actions += MenuAction(
            Icons.Filled.Edit,
            getString(R.string.edit_source),
            onClick = {
                viewModel.rssSource?.let {
                    editSourceResult.launch {
                        putExtra("sourceUrl", it.sourceUrl)
                    }
                }
            }
        )
        actions += MenuAction(
            Icons.Filled.GridView,
            getString(R.string.switchLayout),
            onClick = {
                viewModel.switchLayout()
                upFragments()
            }
        )
        actions += MenuAction(
            Icons.Filled.History,
            getString(R.string.read_record),
            onClick = { showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl)) }
        )
        actions += MenuAction(
            Icons.Filled.Delete,
            getString(R.string.clear),
            onClick = {
                viewModel.url?.let {
                    viewModel.clearArticles()
                }
            }
        )
        return actions
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

    // 保存当前选中位置
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_POSITION", binding.viewPager.currentItem)
    }

    // 恢复状态
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val position = savedInstanceState.getInt("CURRENT_POSITION", 0)
        binding.viewPager.currentItem = position
        updateTabSelection(position)
    }

    // 在onDestroy中释放资源
    override fun onDestroy() {
        super.onDestroy()
        fragmentMap.clear()
        tabScrollViews.clear()
        tabRows.clear()
    }

    fun updatePageMenu(page: Int, visible: Boolean) {
        pageMenuTitle = if (visible) getString(R.string.menu_page, page) else null
    }

    private val currentArticlesFragment: RssArticlesFragment?
        get() {
            val position = binding.viewPager.currentItem
            val sortName = sortList.getOrNull(position)?.first ?: return null
            return fragmentMap[sortName] as? RssArticlesFragment
        }

    private fun upFragments() {
        lifecycleScope.launch {
            val source = viewModel.rssSource ?: return@launch
            if (viewModel.searchKey != null) {
                sortList.apply {
                    val name = "搜索"
                    var url = source.searchUrl ?: return@apply
                    // 如果 searchUrl 是 JS，在独立线程预执行避免协程死锁
                    if (url.startsWith("<js>", true) || url.startsWith("@js:", true)) {
                        url = source.getSearchUrl(viewModel.searchKey!!) ?: url
                    }
                    clear()
                    add(Pair(name, url))
                }
                upFragmentsView()
                return@launch
            }
            viewModel.sortUrl?.takeIf { it.isNotBlank() }?.let { url ->
                val urls: List<Pair<String, String>> = try {
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
                sortList.apply {
                    clear()
                    addAll(urls)
                }
                upFragmentsView()
                return@launch
            }
            if (sortUrls == null) {
                sortUrls = source.sortUrls()
            }
            sortUrls?.let { urls ->
                sortList.apply {
                    clear()
                    addAll(urls)
                }
                upFragmentsView()
                return@launch
            }
        }
    }
    private fun upFragmentsView() {
        if (sortList.size == 1) {
            sortList.first().first.takeIf { it.isNotEmpty() }?.let {
                composeTitle = viewModel.searchKey ?: it
            }
            binding.tabsContainer.gone()
        } else {
            composeTitle = viewModel.sourceName ?: ""
            binding.tabsContainer.visible()
            setupMultiLineTabs()
        }
        adapter.notifyDataSetChanged()
        if (sortList.isNotEmpty()) {
            updateTabSelection(binding.viewPager.currentItem)
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

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return sortList[position].first
        }

        override fun getItem(position: Int): Fragment {
            val sort = sortList[position]
            return RssArticlesFragment(sort.first, sort.second, viewModel.searchKey) //获取内容界面
        }

        override fun getCount(): Int {
            return sortList.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment
            fragmentMap[sortList[position].first] = fragment
            return fragment
        }
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