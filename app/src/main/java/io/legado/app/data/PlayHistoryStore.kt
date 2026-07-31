package io.legado.app.data

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.PlayHistory
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AD-04: 播放历史持久化 Helper（跨会话进度恢复）
 *
 * 接口：
 * - save(articleUrl, videoUrl, position, duration): 异步保存播放进度
 * - load(articleUrl, videoUrl): 同步查询播放历史（需在 IO 线程调用）
 * - clear(articleUrl, videoUrl): 异步清除指定记录
 *
 * 安全性：
 * - save/clear 使用 runCatching 包裹，失败仅 AppLog.put 记录，不影响主播放链路
 * - load 返回 null 时不影响主播放链路
 * - 读取 VideoPlay.playerHistoryEnabled 配置，关闭时不保存
 */
object PlayHistoryStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 异步保存播放进度
     *
     * @param articleUrl 文章链接（无文章时传空字符串）
     * @param videoUrl 视频 URL
     * @param position 当前播放位置（毫秒）
     * @param duration 视频总时长（毫秒）
     * @param rssSourceId RSS 源 ID（可选）
     */
    fun save(
        articleUrl: String,
        videoUrl: String,
        position: Long,
        duration: Long,
        rssSourceId: String = ""
    ) {
        if (!VideoPlay.playerHistoryEnabled) return
        if (videoUrl.isBlank()) return

        scope.launch {
            kotlin.runCatching {
                val history = PlayHistory(
                    articleUrl = articleUrl,
                    videoUrl = videoUrl,
                    position = position,
                    duration = duration,
                    lastPlayTime = System.currentTimeMillis(),
                    rssSourceId = rssSourceId
                )
                appDb.playHistoryDao.insert(history)
            }.onFailure { e ->
                AppLog.put("PlayHistoryStore: save failed, error=${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 同步查询播放历史（需在 IO 线程调用）
     *
     * @param articleUrl 文章链接（无文章时传空字符串）
     * @param videoUrl 视频 URL
     * @return PlayHistory? 无记录或关闭历史功能时返回 null
     */
    suspend fun load(articleUrl: String, videoUrl: String): PlayHistory? =
        withContext(Dispatchers.IO) {
            if (!VideoPlay.playerHistoryEnabled) return@withContext null
            if (videoUrl.isBlank()) return@withContext null

            kotlin.runCatching {
                appDb.playHistoryDao.get(articleUrl, videoUrl)
            }.onFailure { e ->
                AppLog.put("PlayHistoryStore: load failed, error=${e.javaClass.simpleName}")
            }.getOrNull()
        }

    /**
     * 异步清除指定播放历史
     */
    fun clear(articleUrl: String, videoUrl: String) {
        scope.launch {
            kotlin.runCatching {
                appDb.playHistoryDao.delete(articleUrl, videoUrl)
            }.onFailure { e ->
                AppLog.put("PlayHistoryStore: clear failed, error=${e.javaClass.simpleName}")
            }
        }
    }
}
