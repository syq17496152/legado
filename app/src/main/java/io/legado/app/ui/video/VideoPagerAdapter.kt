package io.legado.app.ui.video

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.legado.app.model.VideoPlay

/**
 * R3 抖音风格视频播放 ViewPager2 适配器
 *
 * 多模式策略（优先级从高到低）：
 * - 书源模式（book != null）：若 episodes（书源剧集）非空则按集数分页，垂直滑动切换上/下集；
 *   否则单 Fragment，ViewPager2 禁用滑动
 * - 单URL模式（singleUrl）：单 Fragment，ViewPager2 禁用滑动
 * - 文章列表模式（rssArticles != null）：rssArticles.size 个 Fragment，垂直滑动切换文章
 * - 集数列表模式（rssEpisodes != null）：rssEpisodes.size 个 Fragment，垂直滑动切换集数（旧逻辑兼容）
 * - 兜底：单 Fragment
 */
class VideoPagerAdapter(
    fragmentActivity: FragmentActivity
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int {
        val book = VideoPlay.book
        if (book != null) {
            // 书源模式：有剧集列表则按剧集分页（能力迁移：上下滑动切换上/下集），否则单页
            val episodes = VideoPlay.episodes
            return if (episodes.isNullOrEmpty()) 1 else episodes.size
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
