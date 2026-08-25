package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.DownloadService
import io.legado.app.service.DownloadTaskType
import io.legado.app.utils.startService
import org.json.JSONObject

object Download {

    /**
     * 发起下载任务
     *
     * @param url      下载地址
     * @param fileName 默认文件名（视频标题），为空时服务端回退使用 URL 文件名
     * @param taskType 直链 or m3u8（为空时按 URL 后缀自动判断）
     * @param headers  下载请求头（防盗链 Referer/Cookie 等），透传给下载引擎
     */
    fun start(
        context: Context,
        url: String,
        fileName: String? = null,
        taskType: DownloadTaskType? = null,
        headers: Map<String, String>? = null
    ) {
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
            putExtra("taskType", taskType?.name)
            if (!headers.isNullOrEmpty()) {
                putExtra("headers", JSONObject(headers).toString())
            }
        }
    }

}