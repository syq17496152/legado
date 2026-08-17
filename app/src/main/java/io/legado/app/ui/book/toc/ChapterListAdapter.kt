package io.legado.app.ui.book.toc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ItemChapterListBinding
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.ThemeUtils
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ChapterListAdapter(context: Context, val callback: Callback) :
    DiffRecyclerAdapter<BookChapter, ItemChapterListBinding>(context) {

    val cacheFileNames = hashSetOf<String>()
    private val displayTitleMap = ConcurrentHashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())

    // 分卷折叠: 全量列表 + 折叠卷集合, 展示列表由 applyCollapse 派生 (sync-upstream-optimizations-20260816 R6)
    private var allItems: List<BookChapter> = emptyList()
    private val collapsedVolumeUrls = mutableSetOf<String>()
    private var searchKey: String? = null
    private val volumeMatchCounts = mutableMapOf<String, Int>()

    override val diffItemCallback: DiffUtil.ItemCallback<BookChapter>
        get() = object : DiffUtil.ItemCallback<BookChapter>() {

            override fun areItemsTheSame(
                oldItem: BookChapter,
                newItem: BookChapter
            ): Boolean {
                return oldItem.index == newItem.index
            }

            override fun areContentsTheSame(
                oldItem: BookChapter,
                newItem: BookChapter
            ): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
                        && oldItem.url == newItem.url
                        && oldItem.isVip == newItem.isVip
                        && oldItem.isPay == newItem.isPay
                        && oldItem.title == newItem.title
                        && oldItem.tag == newItem.tag
                        && oldItem.wordCount == newItem.wordCount
                        && oldItem.isVolume == newItem.isVolume
            }

        }

    private var upDisplayTileJob: Coroutine<*>? = null

    override fun onCurrentListChanged() {
        super.onCurrentListChanged()
        callback.onListChanged()
    }

    fun clearDisplayTitle() {
        upDisplayTileJob?.cancel()
        displayTitleMap.clear()
    }

    fun upDisplayTitles(startIndex: Int) {
        upDisplayTileJob?.cancel()
        upDisplayTileJob = Coroutine.async(callback.scope) {
            val book = callback.book ?: return@async
            val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
            val replaceBook = book.toReplaceBook()
            val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
            val items = getItems()
            launch {
                for (i in startIndex until items.size) {
                    val item = items[i]
                    if (displayTitleMap[item.title] == null) {
                        ensureActive()
                        val displayTitle = item.getDisplayTitle(replaceRules, useReplace, replaceBook = replaceBook)
                        ensureActive()
                        displayTitleMap[item.title] = displayTitle
                        handler.post {
                            notifyItemChanged(i, true)
                        }
                    }
                }
            }
            launch {
                for (i in startIndex downTo 0) {
                    val item = items[i]
                    if (displayTitleMap[item.title] == null) {
                        ensureActive()
                        val displayTitle = item.getDisplayTitle(replaceRules, useReplace, replaceBook = replaceBook)
                        ensureActive()
                        displayTitleMap[item.title] = displayTitle
                        handler.post {
                            notifyItemChanged(i, true)
                        }
                    }
                }
            }
        }
    }

    private fun getDisplayTitle(chapter: BookChapter): String {
        return displayTitleMap[chapter.title] ?: chapter.title
    }

    /**
     * 装载目录并应用分卷折叠 (R6)
     */
    fun setItemsWithCollapse(items: List<BookChapter>, searchKey: String?) {
        this.searchKey = searchKey
        allItems = items
        countVolumeMatches(items, searchKey)
        setItems(applyCollapse(items))
    }

    fun isVolumeCollapsed(item: BookChapter): Boolean {
        return collapsedVolumeUrls.contains(item.url)
    }

    /**
     * 卷行点击: 展开态点击折叠; 折叠态点击先展开再跳转 (兼容原卷名跳转行为)
     */
    fun toggleVolume(item: BookChapter, onExpandJump: () -> Unit) {
        if (!item.isVolume) return
        if (collapsedVolumeUrls.remove(item.url)) {
            setItems(applyCollapse(allItems))
            onExpandJump()
        } else {
            collapsedVolumeUrls.add(item.url)
            setItems(applyCollapse(allItems))
        }
    }

    fun volumeMatchCount(item: BookChapter): Int {
        return volumeMatchCounts[item.url] ?: 0
    }

    fun currentSearchKey(): String? {
        return searchKey
    }

    private fun applyCollapse(items: List<BookChapter>): List<BookChapter> {
        if (collapsedVolumeUrls.isEmpty()) {
            return items
        }
        val result = arrayListOf<BookChapter>()
        var collapsed = false
        for (item in items) {
            if (item.isVolume) {
                collapsed = collapsedVolumeUrls.contains(item.url)
                result.add(item)
            } else if (!collapsed) {
                result.add(item)
            }
        }
        return result
    }

    // 搜索态统计每卷匹配章节数 (卷行展示 N 章)
    private fun countVolumeMatches(items: List<BookChapter>, searchKey: String?) {
        volumeMatchCounts.clear()
        if (searchKey.isNullOrBlank()) {
            return
        }
        var curVolume: BookChapter? = null
        for (item in items) {
            if (item.isVolume) {
                curVolume = item
                volumeMatchCounts[item.url] = 0
            } else {
                curVolume?.let {
                    volumeMatchCounts[it.url] = (volumeMatchCounts[it.url] ?: 0) + 1
                }
            }
        }
    }

    override fun getViewBinding(parent: ViewGroup): ItemChapterListBinding {
        return ItemChapterListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChapterListBinding,
        item: BookChapter,
        payloads: MutableList<Any>
    ) {
        binding.run {
            val isDur = callback.durChapterIndex() == item.index
            val cached = callback.isLocalBook
                    || item.isVolume
                    || cacheFileNames.contains(item.getFileName())
            if (payloads.isEmpty()) {
                if (isDur) {
                    tvChapterName.setTextColor(context.accentColor)
                } else {
                    tvChapterName.setTextColor(context.getCompatColor(R.color.primaryText))
                }
                tvChapterName.text = getDisplayTitle(item)
                if (item.isVolume) {
                    //卷名，如第一卷 突出显示; 追加折叠状态箭头 (R6)
                    val arrow = if (isVolumeCollapsed(item)) " ▸" else " ▾"
                    tvChapterName.text = tvChapterName.text.toString() + arrow
                    tvChapterItem.setBackgroundColor(context.getCompatColor(R.color.btn_bg_press))
                } else {
                    //普通章节 保持不变
                    tvChapterItem.background =
                        ThemeUtils.resolveDrawable(context, android.R.attr.selectableItemBackground)
                }

                //卷名不显示 去掉了 !item.isVolume，让卷名也显示
                if (!item.tag.isNullOrEmpty()) {
                    //更新时间规则
                    tvTag.text = item.tag
                    tvTag.visible()
                } else {
                    tvTag.gone()
                }
                if (AppConfig.tocCountWords && !item.wordCount.isNullOrEmpty() && !item.isVolume) {
                    //章节字数
                    tvWordCount.text = item.wordCount
                    tvWordCount.visible()
                } else if (item.isVolume && !currentSearchKey().isNullOrBlank()) {
                    //搜索态卷行显示该卷匹配章节数 (R6)
                    val count = volumeMatchCount(item)
                    if (count > 0) {
                        tvWordCount.text = "${count}章"
                        tvWordCount.visible()
                    } else {
                        tvWordCount.gone()
                    }
                } else {
                    tvWordCount.gone()
                }

                if (item.isVip && !item.isPay) {
                    ivLocked.visible()
                } else {
                    ivLocked.gone()
                }

                upHasCache(binding, isDur, cached)
            } else {
                tvChapterName.text = getDisplayTitle(item)
                upHasCache(binding, isDur, cached)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChapterListBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.isVolume) {
                    //卷行点击切换折叠; 折叠态点击先展开再跳转 (R6)
                    toggleVolume(it) {
                        callback.openChapter(it)
                    }
                } else {
                    callback.openChapter(it)
                }
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                context.longToastOnUi(getDisplayTitle(item))
            }
            true
        }
    }

    private fun upHasCache(binding: ItemChapterListBinding, isDur: Boolean, cached: Boolean) =
        binding.apply {
            ivChecked.setImageResource(R.drawable.ic_outline_cloud_24)
            ivChecked.visible(!cached)
            if (isDur) {
                ivChecked.setImageResource(R.drawable.ic_check)
                ivChecked.visible()
            }
        }

    interface Callback {
        val scope: CoroutineScope
        val book: Book?
        val isLocalBook: Boolean
        fun openChapter(bookChapter: BookChapter)
        fun durChapterIndex(): Int
        fun onListChanged()
    }

}