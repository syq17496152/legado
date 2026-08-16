package io.legado.app.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogSourceFolderConfigBinding
import io.legado.app.databinding.ItemSourceFolderGridBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import kotlin.math.max

/**
 * F-P1-8 书源/订阅源文件夹视图 Adapter
 *
 * 通用文件夹卡片 Adapter，书源和订阅源管理界面共用。
 * 数据项为分组名称（String），点击触发 [CallBack.onFolderClick]。
 * 卡片样式复用书架 grid 布局：3:4 比例主题色封面 + 分组名首字占位 + 下方分组名。
 *
 * 简化说明：不显示分组内源数量 | 已知上限：书源分组是共享的（一个源可属多个分组），数量含义不明确 | 升级路径：如需显示数量，新增 DAO COUNT 查询并扩展数据模型为 data class
 */
class SourceFolderAdapter(
    context: Context,
    private val callBack: CallBack
) : RecyclerAdapter<String, ItemSourceFolderGridBinding>(context) {

    val diffItemCallback = object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem
    }

    override fun getViewBinding(parent: ViewGroup): ItemSourceFolderGridBinding {
        return ItemSourceFolderGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSourceFolderGridBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        binding.tvFolderName.text = item
        binding.tvFolderInitial.text = item.firstCodePointAsString()
        // 对齐书架分组（FolderGroupGridContent）：showBookname==1 时隐藏分组名，仅保留主题色封面+首字
        binding.tvFolderName.isVisible = AppConfig.showBookname != 1
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSourceFolderGridBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.onFolderClick(it) }
        }
    }

    interface CallBack {
        fun onFolderClick(group: String)
    }

    companion object {
        /**
         * source-layout-deep-refactor 显示布局配置对话框
         *
         * 配置项：分组样式(3) + 视图模式(7) + 排序(6) + 间距(0-60)
         * 所有配置在此方法内保存，调用方通过 onConfigChanged 回调刷新视图。
         *
         * @param context 上下文
         * @param onConfigChanged 配置变更回调（任意配置变更即触发）
         */
        fun showConfigDialog(
            context: Context,
            isBookSource: Boolean,
            onConfigChanged: () -> Unit
        ) {
            context.alert(titleResource = R.string.source_folder_config) {
                val binding = DialogSourceFolderConfigBinding
                    .inflate(LayoutInflater.from(context))
                    .apply {
                        // D2 修复：订阅源也支持文件夹视图，不再隐藏分组样式选项
                        spGroupStyle.setSelection(AppConfig.sourceGroupStyle)
                        // D1: 展示模式（标签/分组）
                        spGroupMode.setSelection(AppConfig.sourceGroupMode)
                        rgLayout.checkByIndex(AppConfig.sourceLayout)
                        // C-01 修复：按 Activity 类型区分 bookSourceSort/rssSort
                        rgSort.checkByIndex(if (isBookSource) AppConfig.bookSourceSort else AppConfig.rssSort)
                        sbMargin.progress = AppConfig.sourceMargin
                    }
                customView { binding.root }
                okButton {
                    binding.apply {
                        var changed = false
                        // D2 修复：书源和订阅源统一保存 sourceGroupStyle
                        if (AppConfig.sourceGroupStyle != spGroupStyle.selectedItemPosition) {
                            AppConfig.sourceGroupStyle = spGroupStyle.selectedItemPosition
                            changed = true
                        }
                        // D1: 保存展示模式（标签/分组）
                        if (AppConfig.sourceGroupMode != spGroupMode.selectedItemPosition) {
                            AppConfig.sourceGroupMode = spGroupMode.selectedItemPosition
                            changed = true
                        }
                        val newLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.sourceLayout != newLayout) {
                            AppConfig.sourceLayout = newLayout
                            changed = true
                        }
                        val newSort = rgSort.getCheckedIndex()
                        // C-01 修复：按 Activity 类型区分 bookSourceSort/rssSort
                        if (isBookSource) {
                            if (AppConfig.bookSourceSort != newSort) {
                                AppConfig.bookSourceSort = newSort
                                changed = true
                            }
                        } else {
                            if (AppConfig.rssSort != newSort) {
                                AppConfig.rssSort = newSort
                                changed = true
                            }
                        }
                        if (AppConfig.sourceMargin != sbMargin.progress) {
                            AppConfig.sourceMargin = sbMargin.progress
                            changed = true
                        }
                        if (changed) onConfigChanged()
                    }
                }
                cancelButton()
            }
        }

        /**
         * F-P1-8 根据间距和屏幕宽度动态计算 Grid 列数。
         * 间距越大列数越少（卡片越大），最小 2 列。
         * @param marginDp 间距（dp）
         */
        fun calculateSpanCount(context: Context, marginDp: Int): Int {
            val dm = context.resources.displayMetrics
            val marginPx = (marginDp * dm.density).toInt()
            val minCardWidthPx = (90 * dm.density).toInt() // 最小卡片宽度 90dp
            return max(2, (dm.widthPixels + marginPx) / (minCardWidthPx + marginPx))
        }

        /** F-P1-8 dp 转 px */
        fun spacingPx(context: Context, marginDp: Int): Int {
            return (marginDp * context.resources.displayMetrics.density).toInt()
        }
    }
}

/**
 * 截取字符串首个 code point（兼容 emoji 等 surrogate pair 字符）。
 * 空串返回空串。
 */
private fun String.firstCodePointAsString(): String {
    if (isEmpty()) return ""
    val cp = codePointAt(0)
    return String(Character.toChars(cp))
}
