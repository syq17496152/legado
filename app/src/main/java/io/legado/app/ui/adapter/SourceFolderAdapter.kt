package io.legado.app.ui.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.SourceGroupCover
import io.legado.app.databinding.ItemSourceFolderGridBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.isDarkTheme
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import kotlin.math.max

/**
 * F-P1-8 书源/订阅源文件夹视图 Adapter
 *
 * 通用文件夹卡片 Adapter，书源和订阅源管理界面共用。
 * 数据项为 [FolderItem]（分组 key + 显示名 + 是否特殊分组）。
 * 支持点击（[CallBack.onFolderClick]）与长按（[CallBack.onFolderSelectImage]/[CallBack.onFolderRestoreCover]）。
 * 卡片样式复用书架 grid 布局：3:4 比例主题色封面 + 分组名首字占位 + 下方分组名。
 * 分组封面来自 source_group_covers 表（[SourceGroupCover]），由调用方通过 [upCovers] 提供。
 *
 * 简化说明：不显示分组内源数量 | 已知上限：书源分组是共享的（一个源可属多个分组），数量含义不明确 | 升级路径：如需显示数量，新增 DAO COUNT 查询并扩展数据模型为 data class
 */
class SourceFolderAdapter(
    context: Context,
    private val kind: String,
    private val callBack: CallBack
) : RecyclerAdapter<FolderItem, ItemSourceFolderGridBinding>(context) {

    private val coverCache = HashMap<String, String?>()

    /** 封面占位底色：主题背景色向黑/白微混，营造与 M3 surfaceContainerHigh 同等的层次（跟随主题切换） */
    private val coverPlaceholderColor = ColorUtils.blendARGB(
        context.backgroundColor,
        if (context.isDarkTheme) Color.WHITE else Color.BLACK,
        if (context.isDarkTheme) 0.08f else 0.06f
    )

    /** 默认图标 tint：onSurfaceVariant 的 View 体系等价（主题文字次色） */
    private val folderIconColor = context.secondaryTextColor

    val diffItemCallback = object : DiffUtil.ItemCallback<FolderItem>() {
        override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean =
            oldItem.groupKey == newItem.groupKey

        override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean =
            oldItem.groupKey == newItem.groupKey
    }

    override fun getViewBinding(parent: ViewGroup): ItemSourceFolderGridBinding {
        return ItemSourceFolderGridBinding.inflate(inflater, parent, false).apply {
            // 页面主题为 AppCompat（非 Material3），布局不可用 ?attr/colorSurfaceContainerHigh
            // 等 M3 属性（运行时 InflateException），底色与图标 tint 在此运行时注入
            ivFolderCover.setBackgroundColor(coverPlaceholderColor)
            ivFolderIcon.imageTintList = ColorStateList.valueOf(folderIconColor)
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSourceFolderGridBinding,
        item: FolderItem,
        payloads: MutableList<Any>
    ) {
        binding.tvFolderName.text = item.groupLabel
        // 对齐书架分组（FolderGroupGridContent）：showBookname==1 时隐藏分组名，仅保留封面
        binding.tvFolderName.isVisible = AppConfig.showBookname != 1
        loadFolderCover(binding, item)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSourceFolderGridBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let { callBack.onFolderClick(it) }
        }
        binding.root.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { folder ->
                context.showComposeChoiceListDialog(
                    title = "",
                    labels = buildLongClickActions(folder)
                ) { i ->
                    when (i) {
                        0 -> callBack.onFolderSelectImage(folder)
                        1 -> callBack.onFolderRestoreCover(folder)
                    }
                }
            }
            true
        }
    }

    private fun buildLongClickActions(folder: FolderItem): List<CharSequence> {
        val actions = ArrayList<CharSequence>(2)
        actions.add(context.getString(R.string.select_image))
        if (!coverCache[folder.groupKey].isNullOrEmpty()) {
            actions.add(context.getString(R.string.restore_default))
        }
        return actions
    }

    private fun loadFolderCover(binding: ItemSourceFolderGridBinding, item: FolderItem) {
        val cover = coverCache[item.groupKey]
        if (cover.isNullOrEmpty()) {
            // 无自定义封面：对齐书架分组默认样式（FolderOpen 图标 + 主题 surfaceContainerHigh 底色）
            binding.ivFolderIcon.isVisible = true
            binding.ivFolderCover.setImageDrawable(null)
        } else {
            binding.ivFolderIcon.isVisible = false
            BookCover.load(context, cover).into(binding.ivFolderCover)
        }
    }

    /**
     * 更新指定分组的封面缓存并刷新对应卡片。
     * 选图/恢复默认封面后由调用方触发。
     */
    fun updateCover(groupKey: String, cover: String?) {
        coverCache[groupKey] = cover
        getItems().indexOfFirst { it.groupKey == groupKey }.takeIf { it >= 0 }?.let { index ->
            getItem(index)?.let { item -> updateItem(item) }
        }
    }

    /**
     * 批量填充封面缓存（用于文件夹视图初始化）。
     */
    fun upCovers(covers: Map<String, String?>) {
        coverCache.putAll(covers)
        notifyDataSetChanged()
    }

    interface CallBack {
        fun onFolderClick(folder: FolderItem)

        fun onFolderSelectImage(folder: FolderItem)

        fun onFolderRestoreCover(folder: FolderItem)
    }

    companion object {
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

        /**
         * 特殊分组字符串资源 → 固定英文 key 映射。
         * 与 source_group_covers 表约定一致，跨本地化解耦。
         */
        fun specialFolderKey(@StringRes resId: Int): String = when (resId) {
            R.string.all_groups -> SourceGroupCover.KEY_ALL_GROUPS
            R.string.no_group -> SourceGroupCover.KEY_NO_GROUP
            R.string.type_text -> SourceGroupCover.KEY_TYPE_TEXT
            R.string.type_audio -> SourceGroupCover.KEY_TYPE_AUDIO
            R.string.type_image -> SourceGroupCover.KEY_TYPE_IMAGE
            R.string.type_file -> SourceGroupCover.KEY_TYPE_FILE
            R.string.type_video -> SourceGroupCover.KEY_TYPE_VIDEO
            R.string.type_web -> SourceGroupCover.KEY_TYPE_WEB
            else -> error("未知特殊分组资源: $resId")
        }
    }
}

/**
 * 文件夹卡片数据项。
 * @param groupKey 分组唯一 key：真实分组=分组名；特殊分组=固定英文 key（见 [SourceGroupCover.KEY_ALL_GROUPS] 等）
 * @param groupLabel 显示名称（特殊分组为本地化文本）
 * @param isSpecial 是否为特殊分组（全部/未分组/类型 folder）
 */
data class FolderItem(
    val groupKey: String,
    val groupLabel: String,
    val isSpecial: Boolean
)