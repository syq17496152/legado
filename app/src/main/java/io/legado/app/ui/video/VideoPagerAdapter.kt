package io.legado.app.ui.video

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.legado.app.model.VideoPlay

/**
 * R3 抖音风格视频播放 ViewPager2 适配器
 *
 * 多模式策略（优先级从高到低）：
 * - 书源模式（book != null）：video-booksource-align-rss AD-01 单页化——恒 1 页（禁滑动翻页），
 *   集数/线路切换仅经选择器与详情抽屉；上滑=列表下一影片（Activity 手势层驱动 switchToBookFromList）
 * - 单URL模式（singleUrl）：单 Fragment，ViewPager2 禁用滑动
 * - 文章列表模式（rssArticles != null）：rssArticles.size 个 Fragment，垂直滑动切换文章
 * - 集数列表模式（rssEpisodes != null）：rssEpisodes.size 个 Fragment，垂直滑动切换集数（旧逻辑兼容）
 * - 兜底：单 Fragment
 */
class VideoPagerAdapter(
    fragmentActivity: FragmentActivity
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int {
        if (VideoPlay.book != null) {
            // video-booksource-align-rss AD-01：书源视频单页化——删除多集多页与占位页扩展，
            // 消除双索引失步/标题错乱/占位页卡死三类状态同步问题
            return 1
        }
        if (VideoPlay.singleUrl) {
            // 单URL模式：单页
            return 1
        }
        // 优先基于 rssArticles 创建 Fragment（上下滑动切换文章）
        val articles = VideoPlay.rssArticles
        return if (articles.isNullOrEmpty()) {
            // 兼容旧逻辑：无文章列表时基于 rssEpisodes（上下滑动切换集数）
            val episodes = VideoPlay.rssEpisodes
            if (episodes.isNullOrEmpty()) 1 else episodes.size
        } else {
            articles.size
        }
    }

    override fun getItemId(position: Int): Long {
        // FragmentStateAdapter 稳定 ID：以 position 为 ID。
        // 数据仅尾部增量（ARTICLES_LOADED notifyItemRangeInserted）或整体重建（notifyDataSetChanged+setCurrentItem(0)），
        // 既有 position 的 ID 保持稳定，避免中间插入导致 Fragment 错位复用。
        return position.toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        // 关键修复：数据收缩后（如线路切换重建、列表变短），ViewPager2 布局时可能引用已移除的 position，
        // 不校验会抛 IndexOutOfBoundsException "Invalid view holder adapter position"（用户 3.26.081817 实测崩溃）。
        return itemId >= 0 && itemId < getItemCount().toLong()
    }

    override fun createFragment(position: Int): Fragment {
        return VideoFragment.newInstance(position)
    }
}
