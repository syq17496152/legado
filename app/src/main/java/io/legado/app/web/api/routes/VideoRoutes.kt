package io.legado.app.web.api.routes

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.VideoKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiContext
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.mcp.McpArgs

/**
 * N 多形态（视频）域路由声明（web-mcp-productization 三期 · design 1.2 · REQ-3-531）。
 *
 * 投影二期 [VideoKernel]（业务零重复，AD-10）。端点与二期 MCP 工具一一对应：
 * video_info / video_play / video_lines / video_episodes / video_config_get /
 * video_config_save / video_history。
 *
 * ⚠️ **videoPlay 与施工卡的差异（如实声明）**：施工卡曾描述「入参 bookUrl/lineIndex/episodeIndex、
 * 出可播地址 m3u8/mp4」；二期**实际交付**的 `video_play` 是**端侧播放控制指令**（入参 `action`），
 * 内核 [VideoKernel.play] 只支持 pause/resume/prev/next/stop。本端点按**内核实际契约**投影，
 * 级别取 [Level.MANAGE]（因是带副作用的控制指令，避免只读令牌可操控播放器）；
 * 可播地址与同源中转（design 5）待内核补齐后另行投影。
 *
 * ⚠️ 内核只回播放态 / 元数据，**不含视频画面**（无帧数据通道）。
 */
object VideoRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // N5 当前视频会话信息（标题 / 线路 / 集数 / 播放态，不含画面）
        ApiRoute(Method.GET, "/videoInfo", Level.READONLY, mcpToolName = "video_info") { _ ->
            ReturnData().setData(VideoKernel.info())
        },

        // N5 端侧播放控制指令（pause / resume / prev / next / stop）
        ApiRoute(Method.GET, "/videoPlay", Level.MANAGE, mcpToolName = "video_play") { ctx ->
            ReturnData().setData(VideoKernel.play(ctx.requireParam("action")))
        },

        // N6 卷 = 线路
        ApiRoute(Method.GET, "/videoLines", Level.READONLY, mcpToolName = "video_lines") { _ ->
            ReturnData().setData(VideoKernel.lines())
        },
        // N6 章 = 集
        ApiRoute(Method.GET, "/videoEpisodes", Level.READONLY, mcpToolName = "video_episodes") { ctx ->
            ReturnData().setData(VideoKernel.episodes(ctx.intParam("routeIndex", 0)))
        },

        // N8 播放器配置（布局模式 / 快进比例 / 音轨 / 增强）
        ApiRoute(Method.GET, "/videoConfigGet", Level.READONLY, mcpToolName = "video_config_get") { _ ->
            ReturnData().setData(VideoKernel.configGet())
        },
        ApiRoute(
            Method.POST, "/videoConfigSave", Level.MANAGE, mcpToolName = "video_config_save"
        ) { ctx ->
            val body = ctx.bodyArgs()
            ReturnData().setData(
                VideoKernel.configSave(
                    autoPlay = body.boolOrNull("autoPlay"),
                    startFull = body.boolOrNull("startFull"),
                    longPressSpeed = body.intOrNull("longPressSpeed"),
                    seekSensitivity = body.intOrNull("seekSensitivity"),
                    muteOnStart = body.boolOrNull("muteOnStart"),
                    videoSkipTime = body.intOrNull("videoSkipTime"),
                    playerType = body.intOrNull("playerType"),
                    layoutMode = body.intOrNull("layoutMode"),
                )
            )
        },

        // N9 播放历史（现有 API 无法枚举 ⇒ 内核如实返回 supported = false）
        ApiRoute(Method.GET, "/videoHistory", Level.READONLY, mcpToolName = "video_history") { ctx ->
            ReturnData().setData(VideoKernel.history(ctx.intParam("limit", 50)))
        },
    )
}

/** 请求体 → 参数访问器（与二期 MCP 工具同口径：类型不匹配按缺失处理）。 */
private fun ApiContext.bodyArgs(): McpArgs =
    McpArgs(GSON.fromJsonObject<JsonObject>(requirePostData()).getOrNull() ?: JsonObject())

/** 取可选布尔：字段缺失或 JSON null ⇒ null（= 内核「沿用现值」语义）。 */
private fun McpArgs.boolOrNull(key: String): Boolean? =
    if (raw().has(key) && !raw().get(key).isJsonNull) bool(key) else null

/** 取可选整数：字段缺失或 JSON null ⇒ null。 */
private fun McpArgs.intOrNull(key: String): Int? =
    if (raw().has(key) && !raw().get(key).isJsonNull) int(key) else null