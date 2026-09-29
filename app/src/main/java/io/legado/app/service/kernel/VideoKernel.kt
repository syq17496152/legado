package io.legado.app.service.kernel

import io.legado.app.constant.IntentAction
import io.legado.app.data.PlayHistoryStore
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * ⑰ 视频域业务内核（web-mcp-productization 二期 · tasks 2.21 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象 / 结构化 Map**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 数据源（全部既有能力，不新增存储）：
 * - 播放态 / 视频配置：`VideoPlay` 属性（`video_config` prefs 的唯一读写入口，报告 ⑰）；
 * - 线路 / 集数：`VideoPlay.rssRoutes` / `rssEpisodes` / `rssRouteIndex` / `rssEpisodeIndex`
 *   —— 报告已确认**订阅源与视频书源共用同一线路模型**（`VideoPlay.kt:1461-1510` 把书源「卷=线路」映射进 `rssRoutes`）；
 * - 端侧指令：`VideoPlayService.onStartCommand` 的 `IntentAction` 分发（报告 ⑰）；
 * - 播放历史：`PlayHistoryStore`（读 `VideoPlay.playerHistoryEnabled` 开关）。
 *
 * ⚠️ **不得返回视频画面 / 截图**：本内核只回**播放状态与元数据**（标题、线路、集数、进度），
 * 任何入参/出参都没有帧数据通道（`description` 层面亦无「看图」语义）。
 *
 * 已知上限 / 降级（如实声明）：
 * 1. `play()` **只支持端侧已暴露的指令**：`pause` / `resume` / `prev` / `next` / `stop`
 *    （经 `IntentAction` 派发给 `VideoPlayService`）。**「跳转 / 倍速 / 换线路」无端侧接口**
 *    —— `VideoPlay.switchToRoute(...)` / `upDurIndex(...)` 均需入参 `GSYBaseVideoPlayer` 实例，
 *    内核不宜持有播放器实例 ⇒ 这些动作返回 `delivered = false` + `limitation`，不静默假装成功；
 * 2. `history(limit)` **降级**：`PlayHistoryDao` 无「列全部」查询、`PlayHistoryStore` 仅有
 *    `load(articleUrl, videoUrl)` 的**按键点查**（报告 §⑰ 已列全部方法）⇒ 现有 API **无法枚举**
 *    播放历史。本方法如实返回 `supported = false` + 替代方法 [historyOf]（按键点查单条）。
 *    升级路径：给 `PlayHistoryDao` 加一条 `order by lastPlayTime desc limit :n` 查询；
 * 3. `info()` 中的 `servicePaused` 取自 `VideoPlayService.pause`（伴生对象只读字段，
 *    报告 §⑰ 未列，已读源码核实 `VideoPlayService.kt:66-68`，只读不写）；
 * 4. 所有 URL（视频地址 / 集地址 / 封面）统一经 [DiagKernel.maskUrl] 打码敏感 query。
 */
object VideoKernel {

    /** 端侧已暴露的播放指令（`VideoPlayService.onStartCommand` 分发）。 */
    val SUPPORTED_ACTIONS = listOf("pause", "resume", "prev", "next", "stop")

    // ============================================================ 播放态

    /** 当前播放态（无会话时各字段为空 / 0 / false）。 */
    private fun playState(): Map<String, Any?> = mapOf(
        "playing" to (VideoPlay.videoUrl != null),
        "servicePaused" to VideoPlayService.pause,
        "hasPlayedSuccessfully" to VideoPlay.hasPlayedSuccessfully,
        "videoUrl" to DiagKernel.maskUrl(VideoPlay.videoUrl),
        "title" to (VideoPlay.book?.name ?: VideoPlay.videoTitle ?: ""),
        "routeIndex" to VideoPlay.rssRouteIndex,
        "episodeIndex" to VideoPlay.rssEpisodeIndex,
        "durChapterPos" to VideoPlay.durChapterPos,
    )

    /** `video_info`：当前视频会话信息（标题 / 线路 / 集数 / 播放态；**不含画面**）。 */
    suspend fun info(): Map<String, Any?> = withContext(IO) {
        val routes = VideoPlay.rssRoutes
        val currentRoute = routes?.getOrNull(VideoPlay.rssRouteIndex)
        mapOf(
            "playing" to (VideoPlay.videoUrl != null),
            "servicePaused" to VideoPlayService.pause,
            "title" to (VideoPlay.book?.name ?: VideoPlay.videoTitle ?: ""),
            "videoUrl" to DiagKernel.maskUrl(VideoPlay.videoUrl),
            "cover" to DiagKernel.maskUrl(VideoPlay.getDisplayCover()),
            "newRoutesMode" to VideoPlay.isNewRoutesMode(),
            "routeCount" to (routes?.size ?: 0),
            "routeIndex" to VideoPlay.rssRouteIndex,
            "episodeCount" to (currentRoute?.episodes?.size
                ?: VideoPlay.rssEpisodes?.size
                ?: VideoPlay.episodes?.size
                ?: 0),
            "episodeIndex" to VideoPlay.rssEpisodeIndex,
            "hasPlayedSuccessfully" to VideoPlay.hasPlayedSuccessfully,
            "durChapterPos" to VideoPlay.durChapterPos,
        )
    }

    // ============================================================ 线路 / 集数

    /** `video_lines`：多线路列表（`rssRoutes`；订阅源与视频书源共用同一模型）。 */
    suspend fun lines(): Map<String, Any?> = withContext(IO) {
        val routes = VideoPlay.rssRoutes.orEmpty()
        mapOf(
            "total" to routes.size,
            "currentIndex" to VideoPlay.rssRouteIndex,
            "lines" to routes.mapIndexed { index, route ->
                mapOf(
                    "index" to index,
                    "name" to route.name,
                    "episodeCount" to route.episodes.size,
                )
            },
        )
    }

    /**
     * `video_episodes`：某线路的剧集列表。
     *
     * @throws IllegalArgumentException 线路不存在（含「当前无播放会话 / 无线路数据」）
     */
    suspend fun episodes(routeIndex: Int): Map<String, Any?> = withContext(IO) {
        val routes = VideoPlay.rssRoutes
        require(!routes.isNullOrEmpty()) { "当前无线路数据（未开始播放或源无线路）" }
        require(routeIndex in routes.indices) {
            "线路不存在：index=$routeIndex（当前线路数=${routes.size}）"
        }
        val route = routes[routeIndex]
        mapOf(
            "routeIndex" to routeIndex,
            "routeName" to route.name,
            "total" to route.episodes.size,
            "currentIndex" to if (routeIndex == VideoPlay.rssRouteIndex) VideoPlay.rssEpisodeIndex else -1,
            "episodes" to route.episodes.mapIndexed { index, episode ->
                mapOf(
                    "index" to index,
                    "title" to episode.title,
                    "url" to DiagKernel.maskUrl(episode.url),
                    "duration" to episode.duration,
                    "cover" to DiagKernel.maskUrl(episode.cover),
                )
            },
        )
    }

    // ============================================================ 端侧指令

    /**
     * `video_play`：下发端侧播放指令，返回「指令已下发 + 播放态」。
     *
     * 支持的 `action`：`pause` / `resume` / `prev` / `next` / `stop`（见 [SUPPORTED_ACTIONS]）。
     * 其余动作（跳转 / 倍速 / 换线路）端侧无接口 ⇒ 返回 `delivered = false` + `limitation`（不假装成功）。
     *
     * @throws IllegalArgumentException `action` 为空
     */
    suspend fun play(action: String): Map<String, Any?> = withContext(IO) {
        require(action.isNotBlank()) { "action 不能为空" }
        val intentAction = when (action.lowercase()) {
            "pause" -> IntentAction.pause
            "resume" -> IntentAction.resume
            "prev" -> IntentAction.prev
            "next" -> IntentAction.next
            "stop" -> IntentAction.stop
            else -> null
        }
        if (intentAction == null) {
            return@withContext mapOf(
                "delivered" to false,
                "action" to action,
                "supportedActions" to SUPPORTED_ACTIONS,
                "limitation" to "端侧仅暴露 pause/resume/prev/next/stop；跳转/倍速/换线路无端侧接口",
                "playState" to playState(),
            )
        }
        appCtx.startService<VideoPlayService> { this.action = intentAction }
        mapOf(
            "delivered" to true,
            "action" to action,
            "supportedActions" to SUPPORTED_ACTIONS,
            "playState" to playState(),
        )
    }

    // ============================================================ 视频配置

    /** `video_config_get`：视频播放偏好（`VideoPlay` 属性，落 `video_config` prefs）。 */
    suspend fun configGet(): Map<String, Any?> = mapOf(
        "autoPlay" to VideoPlay.autoPlay,
        "startFull" to VideoPlay.startFull,
        "longPressSpeed" to VideoPlay.longPressSpeed,
        "seekSensitivity" to VideoPlay.seekSensitivity,
        "muteOnStart" to VideoPlay.muteOnStart,
        "videoSkipTime" to VideoPlay.videoSkipTime,
        "playerType" to VideoPlay.playerType,
        "layoutMode" to VideoPlay.layoutMode,
    )

    /**
     * `video_config_save`：保存视频偏好（**只覆盖传入的非 null 字段**）。
     *
     * 口径：`playerType` 0=AUTO / 1=EXO_PLAYER（setter 内 `coerceIn(0,1)`）；
     * `layoutMode` 0=沉浸式 / 1=传统布局（setter 内非法值回落 0）—— 均由 `VideoPlay` 侧钳制。
     */
    suspend fun configSave(
        autoPlay: Boolean? = null,
        startFull: Boolean? = null,
        longPressSpeed: Int? = null,
        seekSensitivity: Int? = null,
        muteOnStart: Boolean? = null,
        videoSkipTime: Int? = null,
        playerType: Int? = null,
        layoutMode: Int? = null,
    ): Map<String, Any?> = withContext(IO) {
        autoPlay?.let { VideoPlay.autoPlay = it }
        startFull?.let { VideoPlay.startFull = it }
        longPressSpeed?.let { VideoPlay.longPressSpeed = it }
        seekSensitivity?.let { VideoPlay.seekSensitivity = it }
        muteOnStart?.let { VideoPlay.muteOnStart = it }
        videoSkipTime?.let { VideoPlay.videoSkipTime = it }
        playerType?.let { VideoPlay.playerType = it }
        layoutMode?.let { VideoPlay.layoutMode = it }
        configGet()
    }

    // ============================================================ 播放历史

    /**
     * `video_history`：播放历史列表。
     *
     * ⚠️ **降级**（见类注释「已知上限 2」）：现有 API 无法枚举播放历史
     * （`PlayHistoryDao` 无列表查询、`PlayHistoryStore` 仅按键点查）⇒ 如实返回 `supported = false`，
     * 单条进度请用 [historyOf]。
     */
    suspend fun history(limit: Int = 50): Map<String, Any?> = withContext(IO) {
        mapOf(
            "supported" to false,
            "limit" to limit,
            "total" to 0,
            "items" to emptyList<Any>(),
            "limitation" to "PlayHistoryDao 无「列全部」查询、PlayHistoryStore 仅按键点查 ⇒ 无法枚举播放历史",
            "hint" to "单条进度请调用 historyOf(articleUrl, videoUrl)",
        )
    }

    /**
     * 按 (articleUrl, videoUrl) 点查单条播放历史（`PlayHistoryStore.load`；历史开关关闭时返回 null）。
     *
     * @param rssSourceId 仅作回告（查询键不含 rssSourceId，与 `PlayHistoryStore.load` 同口径）
     */
    suspend fun historyOf(
        articleUrl: String,
        videoUrl: String,
        rssSourceId: String = "",
    ): Map<String, Any?> {
        require(videoUrl.isNotBlank()) { "videoUrl 不能为空" }
        val record = PlayHistoryStore.load(articleUrl, videoUrl)
        return mapOf(
            "rssSourceId" to rssSourceId,
            "found" to (record != null),
            "articleUrl" to DiagKernel.maskUrl(articleUrl),
            "videoUrl" to DiagKernel.maskUrl(videoUrl),
            "position" to (record?.position ?: 0L),
            "duration" to (record?.duration ?: 0L),
            "lastPlayTime" to (record?.lastPlayTime ?: 0L),
        )
    }
}