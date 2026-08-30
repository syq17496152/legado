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
 * 复用 item_video_chapter 布局，与书源集数列表 UI 一致。
 */
class RssEpisodeAdapter(
    private var episodes: List<RssEpisode>,
    private var selectedPosition: Int = -1,
    private val onEpisodeClick: (RssEpisode, Int) -> Unit
) : RecyclerView.Adapter<RssEpisodeAdapter.EpisodeViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_chapter, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        if (position >= 0 && position < episodes.size) {
            holder.bind(episodes[position], position == selectedPosition)
        }
    }

    override fun getItemCount(): Int = episodes.size

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
            tvChapterName.text = episode.title
            // video-player-image-enhance 样式专项：集数列表为播放页悬浮控件，对齐悬浮层例外体系
            //（color.md 视频控制层：固定白字+半透明黑底；选中 accent 字+accent 20% 透明底，对齐 ChoiceSpeedDialog）
            if (isSelected) {
                tvChapterName.setTextColor(accentColor)
                val accent = accentColor
                tvChapterName.background = GradientDrawable().apply {
                    cornerRadius = 12f * itemView.resources.displayMetrics.density
                    setColor(Color.argb(0x33, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
            } else {
                tvChapterName.setTextColor(Color.WHITE)
                tvChapterName.setBackgroundResource(R.drawable.bg_overlay_button)
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
