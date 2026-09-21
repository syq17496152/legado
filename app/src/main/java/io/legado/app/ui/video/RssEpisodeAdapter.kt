package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.RssEpisode
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor

/**
 * R1 多集选择播放：订阅源集数列表适配器
 *
 * 结构与 ChapterAdapter 对称，但数据类型为 RssEpisode，点击回调用 rssEpisodeIndex。
 * 默认复用 item_video_chapter 布局（沉浸式左下角横向小格子）；
 * 传统模式集数平铺列表传入 layoutRes=item_video_chapter_vertical（纵向整行）。
 */
class RssEpisodeAdapter(
    private var episodes: List<RssEpisode>,
    private var selectedPosition: Int = -1,
    private val verticalLayout: Boolean = false,
    private val onEpisodeClick: (RssEpisode, Int) -> Unit,
) : RecyclerView.Adapter<RssEpisodeAdapter.EpisodeViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        // 2026-09-14 布局重排：传统模式集数纵向平铺滚动（verticalLayout=true → 整行列表项）
        val res = if (verticalLayout) {
            R.layout.item_video_chapter_vertical
        } else {
            R.layout.item_video_chapter
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(res, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        if (position >= 0 && position < episodes.size) {
            holder.bind(episodes[position], position == selectedPosition)
        }
    }

    override fun getItemCount(): Int = episodes.size

    /**
     * F183（2026-09-21）：已看集的原始 URL 集合（与 `RssEpisode.url` 比对）。
     * 默认空集 ⇒ **既有调用点零改动、不标已看**；赋值即整表重绑（集数列表规模小，无需局部刷新）。
     */
    @SuppressLint("NotifyDataSetChanged")
    var watchedUrls: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun updateSelectedPosition(newPosition: Int) {
        if (newPosition < 0 || newPosition >= episodes.size) {
            return
        }
        val oldPosition = selectedPosition
        selectedPosition = newPosition
        if (oldPosition >= 0 && oldPosition < episodes.size) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(newPosition)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newEpisodes: List<RssEpisode>?) {
        this.episodes = newEpisodes ?: return
        notifyDataSetChanged()
    }

    inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvChapterName: TextView = itemView.findViewById(R.id.tvChapterName)

        fun bind(episode: RssEpisode, isSelected: Boolean) {
            // F183：三态——当前（accent，沿用既有 cur 态）/ 已看（次级观感 + ✓ 后缀）/ 未看（默认）
            val watched = !isSelected && watchedUrls.contains(episode.url)
            tvChapterName.text = if (watched) {
                episode.title + itemView.context.getString(R.string.video_episode_watched_mark)
            } else {
                episode.title
            }
            // video-player-image-enhance 样式专项：集数列表为播放页悬浮控件，对齐悬浮层例外体系
            //（color.md 视频控制层：固定白字+半透明黑底；选中 accent 字+accent 20% 透明底，对齐 ChoiceSpeedDialog）
            if (isSelected) {
                tvChapterName.alpha = 1f
                tvChapterName.setTextColor(accentColor)
                val accent = accentColor
                tvChapterName.background = GradientDrawable().apply {
                    cornerRadius = 12f * itemView.resources.displayMetrics.density
                    setColor(Color.argb(0x33, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
            } else {
                tvChapterName.setTextColor(Color.WHITE)
                tvChapterName.setBackgroundResource(R.drawable.bg_overlay_button)
                // 次级观感用 alpha 表达，不新增色值（悬浮层为固定白字体系，无「次级色」单源）
                tvChapterName.alpha = if (watched) 0.6f else 1f
            }
            itemView.setOnClickListener {
                val previousPosition = selectedPosition
                selectedPosition = bindingAdapterPosition
                if (previousPosition >= 0) {
                    notifyItemChanged(previousPosition)
                }
                if (selectedPosition >= 0) {
                    notifyItemChanged(selectedPosition)
                }
                onEpisodeClick(episode, selectedPosition)
            }
        }
    }
}
