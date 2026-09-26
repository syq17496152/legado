package io.legado.app.ui.adapter

import android.content.Context
import kotlin.math.max

/**
 * 书源/订阅源文件夹视图的共享契约与工具（CF 6.2 死件清理）。
 *
 * 原 `SourceFolderAdapter` 是 View 层 `RecyclerAdapter`（inflate `item_source_folder_grid`），
 * 宿主 `RssFragment` 的文件夹目录早已改成 Compose 网格（`SourceFolderComposeGrid`），
 * Adapter 实例再未被创建 ⇒ 仅保留仍被外部引用的三项：
 * `CallBack` 契约、网格列数/间距计算（`calculateSpanCount` / `spacingPx`），
 * 以及数据模型 [FolderItem]（本文件顶层）。
 */
object SourceFolderAdapter {

    interface CallBack {
        fun onFolderClick(folder: FolderItem)

        fun onFolderSelectImage(folder: FolderItem)

        fun onFolderRestoreCover(folder: FolderItem)
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

/**
 * 文件夹卡片数据项。
 * @param groupKey 分组唯一 key：真实分组=分组名；特殊分组=固定英文 key（见 `SourceGroupCover.KEY_ALL_GROUPS` 等）
 * @param groupLabel 显示名称（特殊分组为本地化文本）
 * @param isSpecial 是否为特殊分组（全部/未分组/类型 folder）
 */
data class FolderItem(
    val groupKey: String,
    val groupLabel: String,
    val isSpecial: Boolean
)