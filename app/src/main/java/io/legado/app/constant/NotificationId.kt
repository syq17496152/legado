package io.legado.app.constant

/**
 * 通知ID不能重复,统一规划通知ID
 */
@Suppress("ConstPropertyName")
object NotificationId {

    const val ReadAloudService = 101
    const val AudioPlayService = 102
    const val CacheBookService = 103
    const val ExportBookService = 104
    const val WebService = 105
    const val DownloadService = 106
    const val CheckSourceService = 107
    const val VideoPlayService = 108
    // F-P1-1 自动任务通知 ID
    const val AutoTaskService = 109
    const val AutoTaskBookUpdateBase = 20000
    const val AutoTaskNotifyBase = 21000
    const val Download = 10000
    const val ExportBook = 201

}