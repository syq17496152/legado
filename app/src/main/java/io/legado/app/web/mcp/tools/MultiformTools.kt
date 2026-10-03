package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.MangaKernel
import io.legado.app.service.kernel.VideoKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_MULTIFORM
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_NUMBER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ⑰ 多形态域工具声明（漫画 / 图片 / 视频；web-mcp-productization 二期 · tasks 2.21 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`（REQ-2-305）。
 * 元数据按域单源声明；执行体一律调 Kernel（[MangaKernel] / [VideoKernel]）。
 *
 * 能力边界（spec §4.2.2，措辞不得夸大）：漫画只回**图片 URL 元数据**（不代抓图片）；视频只回
 * **元数据 + 端侧播放指令** —— 画面在手机端渲染，AI 看不到画面（无帧数据通道）。
 *
 * 已知降级（与 Kernel 一致的如实口径，非本文件自行降级）：
 * - `video_play` 端侧仅支 pause / resume / prev / next / stop；跳转 / 倍速 / 换线路无端侧接口；
 * - `video_history` 现有 API 无法枚举历史 ⇒ Kernel 返回 `supported = false` + 原因；
 * - `manga_config_get` 的 `showMangaUi` 为只读属性（无 setter），故不在 `manga_config_save` 入参内。
 */
object MultiformTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "manga_chapters",
            title = "漫画章节目录",
            description = "读取漫画章节目录（**只读已落库元数据**，不发起网络请求）。返回 " +
                "bookUrl/total/loaded/chapters（单条含 index/title/isVolume）；" +
                "loaded = false 表示该书尚未拉取过目录（而非「本书无章节」）。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            MangaKernel.chapters(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "manga_pages",
            title = "漫画章节图片列表",
            description = "读取漫画某章的**图片 URL 列表**（只读元数据，**不代抓图片**）。返回 " +
                "bookUrl/chapterIndex/chapterTitle/isVolume/total/images（URL 已打码敏感参数）；" +
                "纯嗅探型图源在无端侧兜底时可能返回 0 图。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "chapterIndex" to "章节序号（从 0 开始，取 manga_chapters 返回的 index）",
                )
            ),
        ) { args ->
            MangaKernel.pages(args.requireStr("bookUrl"), args.requireInt("chapterIndex"))
        },

        McpTool(
            name = "manga_config_get",
            title = "漫画配置",
            description = "读取漫画阅读配置（缩放 / 预下载 / 自动翻页 / 页脚 / 色滤镜 / 墨水屏 / 灰度等）。" +
                "showMangaUi 为只读属性，仅出现在本返回值中。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            MangaKernel.configGet()
        },

        McpTool(
            name = "manga_config_save",
            title = "保存漫画配置",
            description = "保存漫画阅读配置：**只覆盖传入的字段**，未传字段沿用现值。返回保存后的完整配置。" +
                "showMangaUi 为只读属性，不接受保存。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "disableMangaScale" to TYPE_BOOLEAN,
                    "mangaVolumeKeyPage" to TYPE_BOOLEAN,
                    "disableMangaPageAnim" to TYPE_BOOLEAN,
                    "mangaPreDownloadNum" to TYPE_INTEGER,
                    "mangaAutoPageSpeed" to TYPE_INTEGER,
                    "mangaFooterConfig" to TYPE_STRING,
                    "enableMangaHorizontalScroll" to TYPE_BOOLEAN,
                    "mangaColorFilter" to TYPE_STRING,
                    "hideMangaTitle" to TYPE_BOOLEAN,
                    "enableMangaEInk" to TYPE_BOOLEAN,
                    "mangaEInkThreshold" to TYPE_INTEGER,
                    "enableMangaGray" to TYPE_BOOLEAN,
                ),
                descriptions = mapOf(
                    "disableMangaScale" to "禁用缩放（按原图宽高比）",
                    "mangaVolumeKeyPage" to "卷以按键翻页",
                    "disableMangaPageAnim" to "禁用翻页动画",
                    "mangaPreDownloadNum" to "预下载页数",
                    "mangaAutoPageSpeed" to "自动翻页速度",
                    "mangaFooterConfig" to "页脚配置",
                    "enableMangaHorizontalScroll" to "横向滚动模式",
                    "mangaColorFilter" to "色滤镜（如 gray / 自定义）",
                    "hideMangaTitle" to "隐藏标题",
                    "enableMangaEInk" to "墨水屏模式",
                    "mangaEInkThreshold" to "墨水屏阈值",
                    "enableMangaGray" to "灰度模式",
                )
            ),
        ) { args ->
            // 用 has(...) 区分「未传」与「显式传值」：未传 = null（沿用现值），传空串 = 清空该字段。
            MangaKernel.configSave(
                disableMangaScale = if (args.raw().has("disableMangaScale")) args.bool("disableMangaScale") else null,
                mangaVolumeKeyPage = if (args.raw().has("mangaVolumeKeyPage")) args.bool("mangaVolumeKeyPage") else null,
                disableMangaPageAnim = if (args.raw().has("disableMangaPageAnim")) args.bool("disableMangaPageAnim") else null,
                mangaPreDownloadNum = if (args.raw().has("mangaPreDownloadNum")) args.int("mangaPreDownloadNum") else null,
                mangaAutoPageSpeed = if (args.raw().has("mangaAutoPageSpeed")) args.int("mangaAutoPageSpeed") else null,
                mangaFooterConfig = if (args.raw().has("mangaFooterConfig")) args.str("mangaFooterConfig") else null,
                enableMangaHorizontalScroll = if (args.raw().has("enableMangaHorizontalScroll")) args.bool("enableMangaHorizontalScroll") else null,
                mangaColorFilter = if (args.raw().has("mangaColorFilter")) args.str("mangaColorFilter") else null,
                hideMangaTitle = if (args.raw().has("hideMangaTitle")) args.bool("hideMangaTitle") else null,
                enableMangaEInk = if (args.raw().has("enableMangaEInk")) args.bool("enableMangaEInk") else null,
                mangaEInkThreshold = if (args.raw().has("mangaEInkThreshold")) args.int("mangaEInkThreshold") else null,
                enableMangaGray = if (args.raw().has("enableMangaGray")) args.bool("enableMangaGray") else null,
            )
        },

        McpTool(
            name = "image_gallery_list",
            title = "图片画廊列表",
            description = "读取图片画廊条目（数据源 = App「AI 图库」）。返回 filter/total/returned/groups/images；" +
                "单条含 id/name/prompt/localPath/favorite/groupId/bookName/chapterTitle/characterName 等元数据。" +
                "filter = all（默认）/ temporary / favorite / group（配 group）；search 需 keyword（本工具未暴露入参）。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("filter" to TYPE_STRING, "group" to TYPE_STRING, "limit" to TYPE_INTEGER),
                descriptions = mapOf(
                    "filter" to "筛选口径：all（默认）/ temporary / favorite / group",
                    "group" to "分组 id（filter = group 时使用）",
                    "limit" to "最多返回条数（默认 100，≤0 = 不限）",
                )
            ),
        ) { args ->
            MangaKernel.galleryList(
                filter = args.str("filter") ?: MangaKernel.GALLERY_ALL,
                groupId = args.str("group"),
                limit = args.int("limit", 100),
            )
        },

        McpTool(
            name = "video_info",
            title = "视频会话信息",
            description = "读取当前视频会话元信息（标题 / 线路 / 集数 / 播放态 / 进度 / 封面 URL）。" +
                "**只读元数据，不含画面**（无帧数据通道）；无会话时各字段为空 / 0 / false。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            VideoKernel.info()
        },

        McpTool(
            name = "video_play",
            title = "视频播放控制指令",
            description = "下发播放控制指令给手机端播放器（**端侧指令：下发后回读播放态；AI 看不到画面**）。" +
                "action 取值：pause / resume / prev / next / stop（端侧已暴露的指令）。" +
                "跳转 / 倍速 / 换线路**端侧无接口** ⇒ 返回 delivered = false + limitation，不假装成功；" +
                "speed / chapterIndex / routeIndex 当前不透传（同属端侧无接口项，仅回告）。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf(
                    "speed" to TYPE_NUMBER,
                    "chapterIndex" to TYPE_INTEGER,
                    "routeIndex" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "action" to "pause / resume / prev / next / stop",
                    "speed" to "倍速（端侧暂无接口，传了也不生效）",
                    "chapterIndex" to "跳转集数（端侧暂无接口，传了也不生效）",
                    "routeIndex" to "切换线路（端侧暂无接口，传了也不生效）",
                )
            ),
        ) { args ->
            VideoKernel.play(args.requireStr("action"))
        },

        McpTool(
            name = "video_lines",
            title = "视频线路列表",
            description = "读取视频多线路列表（只读元数据；订阅源与视频书源共用同一线路模型）。" +
                "返回 total/currentIndex/lines（单条含 index/name/episodeCount）。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            VideoKernel.lines()
        },

        McpTool(
            name = "video_episodes",
            title = "视频剧集列表",
            description = "读取某线路的剧集列表（只读元数据）。返回 routeIndex/routeName/total/currentIndex/" +
                "episodes（单条含 index/title/url/duration/cover，URL 已打码）；" +
                "routeIndex 省略按 0（当前线路）处理，无线路数据时返回结构化失败原因。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("routeIndex" to TYPE_INTEGER),
                descriptions = mapOf("routeIndex" to "线路序号（取 video_lines 返回的 index，默认 0）")
            ),
        ) { args ->
            VideoKernel.episodes(args.int("routeIndex", 0))
        },

        McpTool(
            name = "video_config_get",
            title = "视频播放器配置",
            description = "读取视频播放器配置（自动播放 / 起播全屏 / 长按倍速 / 快进比例 / 起播静音 / " +
                "快进秒数 / 播放器类型 / 布局模式）。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            VideoKernel.configGet()
        },

        McpTool(
            name = "video_config_save",
            title = "保存视频播放器配置",
            description = "保存视频播放器配置：**只覆盖传入的字段**，未传沿用现值。playerType 0 = 自动 / " +
                "1 = ExoPlayer；layoutMode 0 = 沉浸式 / 1 = 传统布局（非法值由端侧回落）。返回保存后的完整配置。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "autoPlay" to TYPE_BOOLEAN,
                    "startFull" to TYPE_BOOLEAN,
                    "longPressSpeed" to TYPE_INTEGER,
                    "seekSensitivity" to TYPE_INTEGER,
                    "muteOnStart" to TYPE_BOOLEAN,
                    "videoSkipTime" to TYPE_INTEGER,
                    "playerType" to TYPE_INTEGER,
                    "layoutMode" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "autoPlay" to "自动播放",
                    "startFull" to "起播即全屏",
                    "longPressSpeed" to "长按倍速",
                    "seekSensitivity" to "快进灵敏度",
                    "muteOnStart" to "起播静音",
                    "videoSkipTime" to "快进 / 快退秒数",
                    "playerType" to "播放器类型：0 = 自动 / 1 = ExoPlayer",
                    "layoutMode" to "布局模式：0 = 沉浸式 / 1 = 传统",
                )
            ),
        ) { args ->
            VideoKernel.configSave(
                autoPlay = if (args.raw().has("autoPlay")) args.bool("autoPlay") else null,
                startFull = if (args.raw().has("startFull")) args.bool("startFull") else null,
                longPressSpeed = if (args.raw().has("longPressSpeed")) args.int("longPressSpeed") else null,
                seekSensitivity = if (args.raw().has("seekSensitivity")) args.int("seekSensitivity") else null,
                muteOnStart = if (args.raw().has("muteOnStart")) args.bool("muteOnStart") else null,
                videoSkipTime = if (args.raw().has("videoSkipTime")) args.int("videoSkipTime") else null,
                playerType = if (args.raw().has("playerType")) args.int("playerType") else null,
                layoutMode = if (args.raw().has("layoutMode")) args.int("layoutMode") else null,
            )
        },

        McpTool(
            name = "video_history",
            title = "视频播放历史",
            description = "读取视频播放历史（**降级**：现有 API 无法枚举历史 ⇒ 返回 supported = false + " +
                "limitation 原因说明，不静默返回空列表）。单条续播进度请走端侧按 (articleUrl, videoUrl) 点查。",
            domain = MCP_DOMAIN_MULTIFORM,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("limit" to TYPE_INTEGER),
                descriptions = mapOf("limit" to "最多返回条数（默认 50；当前实现为降级占位，不影响返回结构）")
            ),
        ) { args ->
            VideoKernel.history(args.int("limit", 50))
        },
    )
}