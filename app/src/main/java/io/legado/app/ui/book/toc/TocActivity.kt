@file:Suppress("DEPRECATION")

package io.legado.app.ui.book.toc

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityChapterListBinding
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.thought.ObsidianExportDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 目录
 */
@OptIn(ExperimentalMaterial3Api::class)
class TocActivity : VMBaseActivity<ActivityChapterListBinding, TocViewModel>(),
    TxtTocRuleDialog.CallBack {

    override val binding by viewBinding(ActivityChapterListBinding::inflate)
    override val viewModel by viewModels<TocViewModel>()

    private val waitDialog by lazy { WaitDialog(this) }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.saveBookmark(uri)
                2 -> viewModel.saveBookmarkMd(uri)
            }
        }
    }
    // toc-compose 壳层化：Compose 顶栏状态（TabRow 选中位/搜索/菜单/本地txt）
    private var composeSelectedTab by mutableStateOf(0)
    private var searchActive by mutableStateOf(false)
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var composeIsLocalTxt by mutableStateOf(false)

    // toc-compose 壳层化：菜单动作 ID（原 R.id.menu_xxx，菜单资源已删除）
    private object MenuId {
        const val TOC_REGEX = 1
        const val SPLIT_LONG_CHAPTER = 2
        const val REVERSE_TOC = 3
        const val USE_REPLACE = 4
        const val LOAD_WORD_COUNT = 5
        const val EXPORT_BOOKMARK = 6
        const val EXPORT_MD = 7
        const val EXPORT_OBSIDIAN = 8
        const val LOG = 9
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.viewPager.adapter = TabFragmentPageAdapter()
        viewModel.bookData.observe(this) {
            composeIsLocalTxt = it.isLocalTxt
        }
        // toc-compose 壳层化：ViewPager 与 Compose TabRow 双向联动
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageSelected(position: Int) {
                composeSelectedTab = position
            }

            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {}
        })
        initComposeTopBar()
        intent.getStringExtra("bookUrl")?.let {
            viewModel.initBook(it)
        }
    }

    // toc-compose 壳层化：顶栏（GlassTopAppBar 标题+搜索+更多菜单 / TabRow / 搜索栏）
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.chapter_list),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { if (searchActive) exitSearch() else finish() },
                        actions = {
                            IconButton(onClick = {
                                if (searchActive) exitSearch() else enterSearch()
                            }) {
                                Icon(Icons.Default.Search, contentDescription = null)
                            }
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = null)
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = buildMenuActions()
                                )
                            }
                        }
                    )
                    if (searchActive) {
                        SettingsSearchBar(
                            query = composeSearchQuery,
                            onQueryChange = { upSearch(it) },
                            placeholder = getString(R.string.search),
                            onSearch = { exitSearch() }
                        )
                    } else {
                        TabRow(selectedTabIndex = composeSelectedTab) {
                            Tab(
                                selected = composeSelectedTab == 0,
                                onClick = { switchTab(0) },
                                text = { Text(getString(R.string.chapter_list)) }
                            )
                            Tab(
                                selected = composeSelectedTab == 1,
                                onClick = { switchTab(1) },
                                text = { Text(getString(R.string.bookmark)) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 切换 Tab（Compose TabRow 与 ViewPager 联动）
    private fun switchTab(position: Int) {
        composeSelectedTab = position
        binding.viewPager.setCurrentItem(position, false)
    }

    // 进入搜索：显示搜索栏，隐藏 TabRow
    private fun enterSearch() {
        searchActive = true
        composeSearchQuery = ""
        upSearch("")
    }

    // 退出搜索：隐藏搜索栏，恢复 TabRow
    private fun exitSearch() {
        searchActive = false
        viewModel.searchKey = ""
    }

    // 搜索输入：实时更新目录/书签搜索
    private fun upSearch(text: String) {
        composeSearchQuery = text
        viewModel.searchKey = text
        if (composeSelectedTab == 1) {
            viewModel.startBookmarkSearch(text)
        } else {
            viewModel.startChapterListSearch(text)
        }
    }

    // toc-compose 壳层化：更多菜单数据（按 Tab 动态分组 + 本地txt 组）
    private fun buildMenuActions(): List<MenuAction> {
        return buildList {
            if (composeSelectedTab == 1) {
                // 书签 tab：导出组
                add(MenuAction(Icons.Default.Bookmark, getString(R.string.bookmark), header = true) {})
                add(MenuAction(
                    Icons.Default.IosShare,
                    getString(R.string.export),
                    onClick = { handleMenuAction(MenuId.EXPORT_BOOKMARK) }
                ))
                add(MenuAction(
                    Icons.Default.Description,
                    getString(R.string.export_md),
                    onClick = { handleMenuAction(MenuId.EXPORT_MD) }
                ))
                add(MenuAction(
                    Icons.Default.CloudUpload,
                    getString(R.string.export_to_obsidian),
                    onClick = { handleMenuAction(MenuId.EXPORT_OBSIDIAN) }
                ))
            } else {
                // 目录 tab：目录操作组
                add(MenuAction(Icons.Default.List, getString(R.string.chapter_list), header = true) {})
                add(MenuAction(
                    Icons.Default.SwapVert,
                    getString(R.string.reverse_toc),
                    onClick = { handleMenuAction(MenuId.REVERSE_TOC) }
                ))
                add(MenuAction(
                    Icons.Default.FindReplace,
                    getString(R.string.use_replace),
                    checked = AppConfig.tocUiUseReplace,
                    onClick = { handleMenuAction(MenuId.USE_REPLACE) }
                ))
                add(MenuAction(
                    Icons.Default.Numbers,
                    getString(R.string.load_word_count),
                    checked = AppConfig.tocCountWords,
                    onClick = { handleMenuAction(MenuId.LOAD_WORD_COUNT) }
                ))
                if (composeIsLocalTxt) {
                    add(MenuAction(
                        Icons.Default.Settings,
                        getString(R.string.txt_toc_rule),
                        header = true
                    ) {})
                    add(MenuAction(
                        Icons.Default.Rule,
                        getString(R.string.txt_toc_rule),
                        onClick = { handleMenuAction(MenuId.TOC_REGEX) }
                    ))
                    add(MenuAction(
                        Icons.Default.CallSplit,
                        getString(R.string.split_long_chapter),
                        checked = viewModel.bookData.value?.getSplitLongChapter() == true,
                        onClick = { handleMenuAction(MenuId.SPLIT_LONG_CHAPTER) }
                    ))
                }
            }
            // 日志
            add(MenuAction(Icons.Default.Info, getString(R.string.log), header = true) {})
            add(MenuAction(
                Icons.Default.Info,
                getString(R.string.log),
                onClick = { handleMenuAction(MenuId.LOG) }
            ))
        }
    }

    // toc-compose 壳层化：菜单动作统一入口（原 onCompatOptionsItemSelected 逻辑迁移）
    private fun handleMenuAction(actionId: Int) {
        when (actionId) {
            MenuId.TOC_REGEX -> showDialogFragment(
                TxtTocRuleDialog(viewModel.bookData.value?.tocUrl)
            )

            MenuId.SPLIT_LONG_CHAPTER -> {
                viewModel.bookData.value?.let { book ->
                    book.setSplitLongChapter(!book.getSplitLongChapter())
                    upBookAndToc(book)
                }
            }

            MenuId.REVERSE_TOC -> viewModel.reverseToc {
                viewModel.chapterListCallBack?.upChapterList(
                    if (searchActive) composeSearchQuery else ""
                )
                setResult(RESULT_OK, Intent().apply {
                    putExtra("index", it.durChapterIndex)
                    putExtra("chapterPos", 0)
                })
            }

            MenuId.USE_REPLACE -> {
                AppConfig.tocUiUseReplace = !AppConfig.tocUiUseReplace
                viewModel.chapterListCallBack?.clearDisplayTitle()
                viewModel.chapterListCallBack?.upChapterList(
                    if (searchActive) composeSearchQuery else ""
                )
            }

            MenuId.LOAD_WORD_COUNT -> {
                AppConfig.tocCountWords = !AppConfig.tocCountWords
                viewModel.upChapterListAdapter()
            }

            MenuId.EXPORT_BOOKMARK -> exportDir.launch {
                requestCode = 1
            }

            MenuId.EXPORT_MD -> exportDir.launch {
                requestCode = 2
            }

            MenuId.EXPORT_OBSIDIAN -> viewModel.bookData.value?.let {
                showDialogFragment(ObsidianExportDialog.newInstance(it.name, it.author))
            }

            MenuId.LOG -> showDialogFragment<AppLogDialog>()
        }
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    private fun upBookAndToc(book: Book) {
        waitDialog.show()
        viewModel.upBookTocRule(book) {
            waitDialog.dismiss()
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter :
        FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int): Fragment {
            return when (position) {
                1 -> BookmarkFragment()
                else -> ChapterListFragment()
            }
        }

        override fun getCount(): Int {
            return 2
        }

        override fun getPageTitle(position: Int): CharSequence {
            return when (position) {
                1 -> getString(R.string.bookmark)
                else -> getString(R.string.chapter_list)
            }
        }

    }

}
