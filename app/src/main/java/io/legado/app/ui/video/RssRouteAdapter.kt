package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor

/**
 * R3 多线路支持：线路选择器适配器
 *
 * 结构与 ChapterAdapter/RssEpisodeAdapter 对称，数据为线路名称列表。
 * 复用 item_video_chapter 布局，与书源卷选择器 UI 一致。
 */
class RssRouteAdapter(
    private var routeNames: List<String>,
    private var selectedPosition: Int = -1,
    private val onRouteClick: (String, Int) -> Unit
) : RecyclerView.Adapter<RssRouteAdapter.RouteViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_chapter, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        if (position >= 0 && position < routeNames.size) {
            holder.bind(routeNames[position], position == selectedPosition)
        }
    }

    override fun getItemCount(): Int = routeNames.size

    fun updateSelectedPosition(newPosition: Int) {
        if (newPosition < 0 || newPosition >= routeNames.size) {
            return
        }
        val oldPosition = selectedPosition
        selectedPosition = newPosition
        if (oldPosition >= 0 && oldPosition < routeNames.size) {
            notifyItemChanged(oldPosition)
        }
        notifyItemChanged(newPosition)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newRouteNames: List<String>?) {
        this.routeNames = newRouteNames ?: return
        notifyDataSetChanged()
    }

    inner class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvChapterName: TextView = itemView.findViewById(R.id.tvChapterName)

        fun bind(routeName: String, isSelected: Boolean) {
            tvChapterName.text = routeName
            if (isSelected) {
                tvChapterName.setTextColor(accentColor)
            } else {
                tvChapterName.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.primaryText)
                )
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
                onRouteClick(routeName, selectedPosition)
            }
        }
    }
}
