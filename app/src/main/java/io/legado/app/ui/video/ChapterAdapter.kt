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
import io.legado.app.data.entities.BookChapter
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor

class ChapterAdapter(
    private var chapters: List<BookChapter>,
    private var selectedPosition: Int = -1,
    private val isVolume: Boolean = false,
    private val onChapterClick: (BookChapter, Int) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder>() {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ChapterViewHolder {
        val resourceId = if (isVolume) {
            R.layout.item_video_chapter_volume
        } else {
            R.layout.item_video_chapter
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(resourceId, parent, false)
        return ChapterViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        if (position >= 0 && position < chapters.size) {
            holder.bind(chapters[position], position == selectedPosition)
        }
    }

    override fun getItemCount(): Int = chapters.size

    fun updateSelectedPosition(newPosition: Int) {
        if (newPosition < 0 || newPosition >= chapters.size) {
            return
        }
        val oldPosition = selectedPosition
        selectedPosition = newPosition
        if (oldPosition >= 0 && oldPosition < chapters.size) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(newPosition)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newToc: List<BookChapter>?) {
        this.chapters = newToc ?: return
        notifyDataSetChanged() //全量更新
    }

    inner class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvChapterName: TextView = itemView.findViewById(R.id.tvChapterName)

        fun bind(chapter: BookChapter, isSelected: Boolean) {
            tvChapterName.text = chapter.title
            // video-player-image-enhance 样式专项：章节列表为播放页悬浮控件，对齐悬浮层例外体系（同 RssEpisodeAdapter）
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
                    notifyItemChanged(previousPosition) //更新之前的
                }
                if (selectedPosition >= 0) {
                    notifyItemChanged(selectedPosition) //更新当前选中的
                }
                onChapterClick(chapter, selectedPosition)
            }
        }
    }
}