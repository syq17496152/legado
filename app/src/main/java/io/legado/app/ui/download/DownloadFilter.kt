package io.legado.app.ui.download

import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTask

/**
 * CP-2 单源：下载任务 Tab 过滤（纯函数，无 Android 依赖 ⇒ 可 JVM 单测）。
 *
 * 缺陷背景（2026-09-24 用户报障）：过滤逻辑原内联在 `DownloadManageActivity.filterTasks`，
 * 且**仅**在 `DownloadState.tasks.collect{}` 内被调用 ⇒ 点 Tab 只改 `tabIndex` 状态、
 * 不触发重算，用户感知为「切 Tab 不生效」「上滑后列表收缩消失」（后者是筛后变空
 * 导致整页空态替换列表容器）。抽为纯函数后由宿主在「点 Tab」与「状态发射」双路径统一调用。
 *
 * 排序口径：`startTime` 倒序（新任务在前），与改造前保持一致。
 */
object DownloadFilter {

    /**
     * 按 Tab 下标过滤。
     * 下标越界（含 `tabIndex` 尚未初始化时）按「全部」兜底，与改造前的 `null -> tasks` 行为一致。
     */
    fun apply(tasks: List<DownloadTask>, tabIndex: Int): List<DownloadTask> {
        val filtered = when (DownloadTab.entries.getOrNull(tabIndex)) {
            DownloadTab.ALL, null -> tasks
            // 等待中与下载中同归「下载中」Tab（改造前既有口径）
            DownloadTab.RUNNING -> tasks.filter {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.WAITING
            }
            DownloadTab.PAUSED -> tasks.filter { it.status == DownloadStatus.PAUSED }
            DownloadTab.COMPLETED -> tasks.filter { it.status == DownloadStatus.COMPLETED }
            DownloadTab.FAILED -> tasks.filter { it.status == DownloadStatus.FAILED }
        }
        return filtered.sortedByDescending { it.startTime }
    }
}