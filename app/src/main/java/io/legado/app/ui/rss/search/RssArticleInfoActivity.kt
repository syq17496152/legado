package io.legado.app.ui.rss.search

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ActivityRssArticleInfoBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyTint
import io.legado.app.utils.gone
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 订阅源文章详情页 Activity（rss-unified-search 阶段10 新增，阶段11 重构美化）
 *
 * 仿书源 [io.legado.app.ui.book.info.BookInfoActivity] 的设计：
 * - 顶部 ArcView + CardView + CoverImageView 封面图区域（无图时整体隐藏）
 * - 标题 + 信息行（发布时间/文章类型/来源数量）
 * - 内容简介（ScrollTextView）
 * - 多源列表（RecyclerView，点击切换选中源）
 * - 底部主题色操作栏（返回 + 阅读 AccentBgTextView）
 *
 * 数据来源：[RssSearchSourceHolder]（SearchRssArticle 非 Parcelable，无法通过 Intent 传递）
 * - searchArticle: 文章标题/简介/发布时间/图片/类型
 * - articles: 多源映射（用于显示多源列表）
 * - rssArticles: 搜索结果列表转 RssArticle 列表（传给 ReadRss.readRss 支持播放页上下切换）
 *
 * 交互逻辑：
 * 1. 多源列表每项点击：用该源的 RssArticle 跳阅读页/播放页（传入 rssArticles 支持上下切换）
 * 2. 底部"阅读"按钮：用当前选中源（或默认源）的 RssArticle 跳阅读页/播放页
 * 3. 底部"返回"按钮：finish()
 *
 * 设计依据：rss-unified-search design.md §5（用户反馈"按书源逻辑应有详情页"方案 D）
 * 阶段11 重构依据：用户反馈"详情页真丑+缺图片+不贴合整体风格+学习书源详情页"
 */
class RssArticleInfoActivity :
    BaseActivity<ActivityRssArticleInfoBinding>() {

    override val binding by viewBinding(ActivityRssArticleInfoBinding::inflate)

    private val sourceAdapter by lazy { RssArticleInfoSourceAdapter(this) }

    /**当前选中的源的 origin（sourceUrl），默认取 articles 的第一个 key**/
    private var selectedOrigin: String? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setBackgroundColor(primaryColor)
        binding.titleBar.setTitle(R.string.rss_article_info_title)
        binding.root.setBackgroundColor(backgroundColor)
        binding.titleBar.applyTint(accentColor)
        binding.rvSourceList.layoutManager = LinearLayoutManager(this)
        binding.rvSourceList.adapter = sourceAdapter
        binding.rvSourceList.applyNavigationBarMargin()

        sourceAdapter.setOnSourceClickListener(object : RssArticleInfoSourceAdapter.OnSourceClickListener {
            override fun onSourceClick(item: RssArticleInfoSourceAdapter.SourceItem?) {
                item?.let { sourceItem ->
                    selectedOrigin = sourceItem.origin
                    sourceAdapter.setSelected(sourceItem.origin)
                    // 选中后立即跳阅读页（与书源详情页点击章节即阅读行为一致）
                    startRead(sourceItem.rssArticle)
                }
            }
        })

        binding.tvRead.setOnClickListener {
            // 用当前选中源（或默认源）的 RssArticle 跳阅读页
            val article = getSelectedArticle() ?: run {
                finish()
                return@setOnClickListener
            }
            startRead(article)
        }

        binding.tvCancel.setOnClickListener {
            finish()
        }

        loadData()
    }

    /**
     * 从 Holder 读取数据并填充 UI
     *
     * 阶段11 重构：新增封面图加载 + 文章类型/来源数量信息行
     */
    private fun loadData() {
        val searchArticle = RssSearchSourceHolder.searchArticle ?: run {
            finish()
            return
        }
        val articlesMap = RssSearchSourceHolder.articles ?: run {
            finish()
            return
        }

        // 标题与基本信息
        binding.tvTitle.text = searchArticle.title
        binding.tvPubDate.text = searchArticle.pubDate?.takeIf { it.isNotBlank() }
            ?: getString(R.string.rss_article_info_no_pubdate)
        binding.tvDescription.text = searchArticle.description?.takeIf { it.isNotBlank() }
            ?: getString(R.string.rss_article_info_no_description)

        // 文章类型（0=网页, 1=图片, 2=视频）
        binding.tvType.text = when (searchArticle.type) {
            1 -> getString(R.string.rss_article_type_image)
            2 -> getString(R.string.rss_article_type_video)
            else -> getString(R.string.rss_article_type_web)
        }

        // 来源数量
        val sourceCount = articlesMap.size
        binding.tvSourceCount.text = getString(R.string.rss_source_count_format, sourceCount)

        // 封面图：无图时隐藏整个封面区域（包括 ArcView+CardView）
        val imageUrl = searchArticle.image?.takeIf { it.isNotBlank() }
        if (imageUrl == null) {
            binding.rlCoverContainer.gone()
        } else {
            binding.rlCoverContainer.visible()
            // 取默认源的 origin 用于图片加载（部分源需要 referer/cookie）
            val defaultOrigin = articlesMap.keys.firstOrNull() ?: ""
            val options = RequestOptions().set(
                OkHttpModelLoader.sourceOriginOption, defaultOrigin
            ).diskCacheStrategy(DiskCacheStrategy.ALL)
            ImageLoader.load(this, imageUrl).apply(options).apply {
                placeholder(R.drawable.image_rss_article)
                addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.rlCoverContainer.gone()
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        binding.rlCoverContainer.visible()
                        return false
                    }
                })
            }.into(binding.ivCover)
        }

        // 默认选中第一个源
        selectedOrigin = articlesMap.keys.firstOrNull()

        lifecycleScope.launch {
            val items = withContext(IO) {
                val sourceUrls = articlesMap.keys.toList()
                val rssSources = appDb.rssSourceDao.getRssSources(*sourceUrls.toTypedArray())
                val sourceMap = rssSources.associateBy { it.sourceUrl }
                articlesMap.entries.map { (origin, article) ->
                    RssArticleInfoSourceAdapter.SourceItem(
                        rssSource = sourceMap[origin],
                        rssArticle = article,
                        origin = origin,
                        isSelected = origin == selectedOrigin
                    )
                }
            }
            sourceAdapter.setItems(items)
        }
    }

    /**
     * 获取当前选中源（或默认源）的 RssArticle
     */
    private fun getSelectedArticle(): RssArticle? {
        val articlesMap = RssSearchSourceHolder.articles ?: return null
        val origin = selectedOrigin ?: articlesMap.keys.firstOrNull() ?: return null
        return articlesMap[origin]
    }

    /**
     * 跳转阅读页/播放页
     *
     * 传入 RssSearchSourceHolder.rssArticles 支持播放页上/下一个切换文章
     */
    private fun startRead(rssArticle: RssArticle) {
        val rssArticles = RssSearchSourceHolder.rssArticles
        ReadRss.readRss(this, rssArticle, rssArticles = rssArticles)
    }

    override fun onDestroy() {
        // 不清理 Holder：阅读页/播放页 onDestroy 时会清理（ReadRssActivity/VideoPlayerActivity）
        // 详情页跳阅读页后，阅读页内换源对话框仍需读取 Holder.articles 和 Holder.rssArticles
        super.onDestroy()
    }

}
