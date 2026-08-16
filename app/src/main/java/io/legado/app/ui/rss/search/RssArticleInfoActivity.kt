package io.legado.app.ui.rss.search

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ActivityRssArticleInfoBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
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
        applyThemeColors()
        initComposeTopBar()
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
     * 阶段11.4 问题1 修复：集中应用动态主题色（整体方案：详情页所有元素跟随主题变动）
     *
     * 参考书源 BookInfoActivity 的主题色设置：
     * - 顶栏（GlassTopAppBar）背景色 = surface（由 LegadoTheme 管理，跟随应用主题）
     * - 根布局/ArcView/CardView 背景色 = backgroundColor
     * - 底部操作栏背景色 = bottomBackground（与书源 flAction 一致）
     * - 底部"返回"按钮文字色 = getPrimaryTextColor(根据 bottomBackground 明暗)
     * - SwipeRefresh 配色 = accentColor
     * - "阅读"按钮（AccentBgTextView）背景色跟随 accentColor（init 块只读一次，需手动刷新）
     * - 多源列表 Adapter 选中源文字色 + iv_checked tint 跟随 accentColor（需触发重新绑定）
     *
     * 必须在 onActivityCreated 和 onConfigurationChanged 中都调用，
     * 确保初始化和主题切换后都能正确显示。
     */
    private fun applyThemeColors() {
        binding.root.setBackgroundColor(backgroundColor)
        binding.arcView.setBgColor(backgroundColor)
        // 阶段11.4 问题1 补全：CardView 背景色跟随主题（原默认白色，暗色模式显白块）
        binding.ivCoverC.setCardBackgroundColor(backgroundColor)
        // 阶段11.4 问题1 补全：底部操作栏背景色跟随主题（参考书源 flAction.setBackgroundColor(bottomBackground)）
        binding.llAction.setBackgroundColor(bottomBackground)
        // 阶段11.4 问题1 补全：底部"返回"按钮文字色根据 bottomBackground 明暗自动取色
        binding.tvCancel.setTextColor(getPrimaryTextColor(ColorUtils.isColorLight(bottomBackground)))
        // 阶段11.4 问题1 补全：SwipeRefresh 下拉刷新配色跟随主题强调色
        binding.refreshLayout.setColorSchemeColors(accentColor)
        // 阶段11.4 问题1 整体方案：AccentBgTextView "阅读"按钮响应主题切换（init 块静态读取，需手动刷新）
        binding.tvRead.updateAccentColor()
        // 阶段11.4 问题1 整体方案：多源列表 Adapter 选中色 + iv_checked tint 响应主题切换
        // 注意：onActivityCreated 阶段调用时 itemCount=0 是 no-op；onConfigurationChanged 阶段触发重新绑定
        sourceAdapter.updateThemeColors()
    }

    /**
     * ui-redesign-m3 12.6z 壳层化：顶栏 Compose 化（GlassTopAppBar），
     * 背景/前景色由 LegadoTheme 统一管理，跟随应用主题。
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.rss_article_info_title),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() }
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 阶段11.4 问题1 修复：用户在设置中切换 App 主题后回来，重新应用动态主题色
        applyThemeColors()
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

        // B1 修复：默认选中源用 searchArticle.origins.firstOrNull()（LinkedHashSet 插入顺序）
        // 铁证：articlesMap 是 HashMap，keys 顺序由哈希值决定，不保证与 origins（LinkedHashSet）一致
        //   当 HashMap.keys.firstOrNull() != origins.firstOrNull() 时：
        //   - 默认选中的源 ≠ rssArticles[0] 的源（rssArticles 用 origins.firstOrNull() 生成）
        //   - VideoPlay.switchToArticle(0) 加载 rssArticles[0]，但 source 是默认选中源 → 不匹配 → 播放失败
        selectedOrigin = searchArticle?.origins?.firstOrNull() ?: articlesMap.keys.firstOrNull()
        AppLog.put("RssArticleInfo: selectedOrigin=${selectedOrigin?.take(2)}***, source=origins")

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
