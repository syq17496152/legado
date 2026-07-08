package io.legado.app.ui.book.toc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookHighlight
import io.legado.app.databinding.FragmentBookmarkBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.read.HighlightNoteDialog
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 目录"标注"Tab Fragment, 展示当前书籍的所有手动高亮
 * 复用 fragment_bookmark.xml 布局（与书签 Tab 同结构）
 */
class HighlightFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_bookmark),
    HighlightAdapter.Callback,
    TocViewModel.HighlightCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentBookmarkBinding::bind)
    private var mLayoutManager: UpLinearLayoutManager? = null
    private val adapter by lazy { HighlightAdapter(requireContext(), this) }
    private var durChapterIndex = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.highlightCallBack = this
        initRecyclerView()
        viewModel.bookData.observe(this) {
            durChapterIndex = it.durChapterIndex
            upHighlight(null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // FragmentStateAdapter 会真正销毁离屏页,置空回避免野指针;恒等判断防止清掉新实例的注册
        if (viewModel.highlightCallBack === this) {
            viewModel.highlightCallBack = null
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        mLayoutManager = UpLinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
    }

    override fun upHighlight(searchKey: String?) {
        val book = viewModel.bookData.value ?: return
        lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> appDb.bookHighlightDao.flowByBook(book.name, book.author)
                else -> appDb.bookHighlightDao.flowSearch(book.name, book.author, searchKey)
            }.catch {
                AppLog.put("目录界面获取高亮数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
                var scrollPos = 0
                withContext(Dispatchers.Default) {
                    adapter.getItems().forEachIndexed { index, highlight ->
                        if (highlight.chapterIndex >= durChapterIndex) {
                            return@withContext
                        }
                        scrollPos = index
                    }
                }
                mLayoutManager?.scrollToPositionWithOffset(scrollPos, 0)
            }
        }
    }

    override fun onClick(highlight: BookHighlight) {
        activity?.run {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("index", highlight.chapterIndex)
                putExtra("chapterPos", highlight.chapterPos)
            })
            finish()
        }
    }

    override fun onLongClick(highlight: BookHighlight, pos: Int) {
        showDialogFragment(HighlightNoteDialog(highlight))
    }

}
