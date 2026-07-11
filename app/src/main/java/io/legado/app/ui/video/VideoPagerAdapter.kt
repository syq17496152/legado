package io.legado.app.ui.video

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.legado.app.model.VideoPlay

/**
 * R3 抖音风格视频播放 ViewPager2 适配器
 *
 * 双模式策略：
 * - 书源模式（book != null）：单 Fragment，ViewPager2 禁用滑动
 * - 订阅源模式（book == null）：rssEpisodes.size 个 Fragment，垂直滑动切换
 */
class VideoPagerAdapter(
    fragmentActivity: FragmentActivity
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int {
        val book = VideoPlay.book
        if (book != null) {
            // 书源模式：单页
            return 1
        }
        val episodes = VideoPlay.rssEpisodes
        return if (episodes.isNullOrEmpty()) 1 else episodes.size
    }

    override fun createFragment(position: Int): Fragment {
        return VideoFragment.newInstance(position)
    }
}
