package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.data.entities.TtsCastingTemplate
import io.legado.app.service.kernel.AudioKernel
import io.legado.app.service.kernel.TtsKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_TTS
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_ARRAY
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_NUMBER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑦ TTS 听书域工具声明（web-mcp-productization 二期 · tasks 2.13 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`** —— 配置侧走 [TtsKernel]、控制侧走 [AudioKernel]，
 * 不内联业务、不直接访问 DAO、不 import `api.controller`（REQ-2-305）。
 *
 * 域内分两侧：配置侧（引擎 / HTTP TTS / 朗读参数 / 说话人分组 / 配音模板 / BGM），
 * 控制侧（听书 + 有声书）。控制侧工具均为「**端侧指令：下发后回读播放态**」——
 * 控制方法不阻塞等待播放结果，需确认效果时再调对应的进度工具回读。
 */
object TtsTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "tts_engines_get",
            title = "朗读引擎清单",
            description = "读取朗读引擎清单：内置引擎（跟随系统 / 系统 TTS）+ 已添加的 HTTP TTS 摘要，" +
                "并回告当前引擎。返回 current/builtin/httpEngines/count。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            TtsKernel.engines()
        },

        McpTool(
            name = "http_tts_list",
            title = "HTTP TTS 列表",
            description = "读取全部 HTTP TTS 引擎的完整配置（类型 / 并发 / 线程数 / url 等，url 已打码）。" +
                "返回 total/engines。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            TtsKernel.httpTtsList()
        },

        McpTool(
            name = "http_tts_save",
            title = "保存 HTTP TTS",
            description = "新增 / 覆盖一个 HTTP TTS 引擎（id 相同即覆盖，name 与 url 必填）。" +
                "**写操作**：会落库；返回保存后的引擎对象。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("engine" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("engine" to "HTTP TTS 对象或 JSON 串（name/url 必填）"),
            ),
        ) { args ->
            val spec = parseJson<HttpTTS>(args.raw().get("engine"))
                ?: throw McpParamException("参数 engine 格式不对（须为 HTTP TTS 对象或 JSON 字符串）")
            TtsKernel.httpTtsSave(spec)
        },

        McpTool(
            name = "http_tts_delete",
            title = "删除 HTTP TTS",
            description = "按 id 删除一个 HTTP TTS 引擎。**写操作**：不可逆；返回被删引擎的 id 与 name。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("id" to TYPE_INTEGER),
                descriptions = mapOf("id" to "HTTP TTS 引擎 id（取 http_tts_list 的返回值）"),
            ),
        ) { args ->
            TtsKernel.httpTtsDelete(args.requireInt("id").toLong())
        },

        McpTool(
            name = "tts_config_get",
            title = "朗读参数",
            description = "读取朗读参数（全局：跟随系统 / 语速 / 定时与模式 / 段落停顿）+ 当前引擎级参数" +
                "（语速/音调/音量）。返回参数 Map。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            TtsKernel.configGet()
        },

        McpTool(
            name = "tts_config_save",
            title = "保存朗读参数",
            description = "保存朗读参数，**只覆盖传入的字段**（省略的沿用现值）。" +
                "**写操作**：会落库；结束时回读落地结果并返回参数 Map。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "engine" to TYPE_STRING,
                    "followSystem" to TYPE_BOOLEAN,
                    "speechRate" to TYPE_INTEGER,
                    "timerMinutes" to TYPE_INTEGER,
                    "timerMode" to TYPE_INTEGER,
                    "timerChapters" to TYPE_INTEGER,
                    "paragraphPauseMs" to TYPE_INTEGER,
                    "engineSpeechRate" to TYPE_NUMBER,
                    "enginePitch" to TYPE_NUMBER,
                    "engineVolume" to TYPE_NUMBER,
                ),
                descriptions = mapOf(
                    "engine" to "引擎级参数落在哪个引擎（省略 = 当前引擎）",
                    "followSystem" to "是否跟随系统设置",
                    "speechRate" to "全局朗读语速",
                    "timerMinutes" to "定时停止（分钟）",
                    "timerMode" to "定时模式（1=本章 2=剩余章）",
                    "timerChapters" to "定时章数",
                    "paragraphPauseMs" to "段落停顿（毫秒）",
                    "engineSpeechRate" to "引擎级语速",
                    "enginePitch" to "引擎级音调",
                    "engineVolume" to "引擎级音量",
                )
            ),
        ) { args ->
            val raw = args.raw()
            TtsKernel.configSave(
                engine = args.str("engine"),
                followSystem = if (raw.has("followSystem")) args.bool("followSystem") else null,
                speechRate = if (raw.has("speechRate")) args.int("speechRate") else null,
                timerMinutes = if (raw.has("timerMinutes")) args.int("timerMinutes") else null,
                timerMode = if (raw.has("timerMode")) args.int("timerMode") else null,
                timerChapters = if (raw.has("timerChapters")) args.int("timerChapters") else null,
                paragraphPauseMs = if (raw.has("paragraphPauseMs")) args.int("paragraphPauseMs") else null,
                engineSpeechRate = args.str("engineSpeechRate")?.toFloatOrNull(),
                enginePitch = args.str("enginePitch")?.toFloatOrNull(),
                engineVolume = args.str("engineVolume")?.toFloatOrNull(),
            )
        },

        McpTool(
            name = "tts_test",
            title = "校验试听目标",
            description = "解析并校验试听目标（引擎路由 / 说话人 / 音色），回告该校验后的参数。" +
                "**不发声音**（端侧试听由 UI 层控制器驱动）。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "engineValue" to TYPE_STRING,
                    "speakerName" to TYPE_STRING,
                    "toneId" to TYPE_STRING,
                ),
                descriptions = mapOf(
                    "engineValue" to "引擎标识（省略 = 当前引擎）",
                    "speakerName" to "说话人名（省略 = 路由默认）",
                    "toneId" to "音色 id（省略 = 路由默认）",
                )
            ),
        ) { args ->
            TtsKernel.test(
                engineValue = args.str("engineValue"),
                speakerName = args.str("speakerName"),
                toneId = args.str("toneId"),
            )
        },

        McpTool(
            name = "audio_control",
            title = "听书播放控制",
            description = "听书（TTS 朗读正文）播放控制。action：play/pause/resume/stop/prev_chapter/" +
                "next_chapter/prev_paragraph/next_paragraph/speed_up/set_timer(minute)/" +
                "set_timer_mode(mode,chapters)/select_chapter(chapterIndex)。" +
                "**端侧指令：下发后回读播放态**（不阻塞等待，需确认时再调 audio_progress_get）。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf(
                    "minute" to TYPE_INTEGER,
                    "mode" to TYPE_INTEGER,
                    "chapters" to TYPE_INTEGER,
                    "chapterIndex" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "action" to "播放动作（见说明）",
                    "minute" to "set_timer 的定时分钟数",
                    "mode" to "set_timer_mode 的模式（1=本章 2=剩余章）",
                    "chapters" to "set_timer_mode 的章数",
                    "chapterIndex" to "select_chapter 的目标章节下标",
                )
            ),
        ) { args ->
            AudioKernel.audioControl(
                action = args.requireStr("action"),
                minute = args.int("minute", 0),
                mode = args.int("mode", 0),
                chapters = args.int("chapters", 0),
                chapterIndex = args.int("chapterIndex", 0),
            )
        },

        McpTool(
            name = "audio_progress_get",
            title = "听书播放态",
            description = "读取听书播放态与当前引擎路由（运行/暂停/播放中/定时/剩余章/路由）。只读。" +
                "用于回读 audio_control 下发的指令效果。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AudioKernel.audioProgress()
        },

        McpTool(
            name = "speaker_groups_get",
            title = "说话人分组",
            description = "读取朗读角色分组（含组内成员：引擎/说话人/音色）。返回 total/groups。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            TtsKernel.speakerGroupsGet()
        },

        McpTool(
            name = "speaker_group_save",
            title = "保存说话人分组",
            description = "保存说话人分组：groupId ≤ 0 或省略 = 新增（需 name），否则按 id 更新；" +
                "items 传入时整组覆盖成员。**写操作**：会落库；返回 groupId/itemCount。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "groupId" to TYPE_INTEGER,
                    "name" to TYPE_STRING,
                    "enabled" to TYPE_BOOLEAN,
                    "items" to TYPE_ARRAY,
                ),
                descriptions = mapOf(
                    "groupId" to "分组 id（≤0 / 省略 = 新增）",
                    "name" to "分组名（新增必填）",
                    "enabled" to "是否启用",
                    "items" to "组内成员对象数组（整组覆盖；省略 = 不改成员）",
                )
            ),
        ) { args ->
            val raw = args.raw()
            val items = if (raw.has("items")) {
                parseJsonList<ReadAloudSpeakerGroupItem>(raw.get("items"))
            } else {
                null
            }
            TtsKernel.speakerGroupSave(
                groupId = args.int("groupId", 0).toLong(),
                name = args.str("name"),
                enabled = if (raw.has("enabled")) args.bool("enabled") else null,
                items = items,
            )
        },

        McpTool(
            name = "tts_casting_get",
            title = "配音模板列表",
            description = "读取多角色配音模板列表 + 当前激活模板 id。返回 total/activeTemplateId/templates。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            TtsKernel.castingGet()
        },

        McpTool(
            name = "tts_casting_save",
            title = "保存配音模板",
            description = "新增 / 覆盖多角色配音模板（id 为业务主键，name 必填）。" +
                "**写操作**：会落库；返回保存后的模板对象。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("template" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("template" to "配音模板对象或 JSON 串（id/name 必填）"),
            ),
        ) { args ->
            val template = parseJson<TtsCastingTemplate>(args.raw().get("template"))
                ?: throw McpParamException("参数 template 格式不对（须为配音模板对象或 JSON 字符串）")
            TtsKernel.castingSave(template)
        },

        McpTool(
            name = "read_aloud_bgm_get",
            title = "BGM 分组与音轨",
            description = "读取朗读 BGM 分组与启用音轨，可按 assetType（bgm 配乐 / sfx 音效，省略 = 全部）过滤。" +
                "返回 assetType/groups/tracks。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("assetType" to TYPE_STRING),
                descriptions = mapOf("assetType" to "资产类型：bgm / sfx（省略 = 全部）"),
            ),
        ) { args ->
            TtsKernel.bgmGet(args.str("assetType"))
        },

        McpTool(
            name = "read_aloud_bgm_save",
            title = "保存 BGM 配置",
            description = "保存 BGM 分组与音轨配置（group：id ≤ 0 新增 / 否则更新；tracks：非空时按 id upsert）。" +
                "**写操作**：会落库（不含 ZIP 音频包导入）；返回 groupId/savedTracks。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "group" to McpJsonSchema.TYPE_OBJECT,
                    "tracks" to TYPE_ARRAY,
                ),
                descriptions = mapOf(
                    "group" to "BGM 分组对象（id ≤ 0 新增需 name）",
                    "tracks" to "音轨对象数组（id ≤ 0 新增 / 否则更新）",
                )
            ),
        ) { args ->
            val raw = args.raw()
            val group = if (raw.has("group")) parseJson<ReadAloudBgmGroup>(raw.get("group")) else null
            val tracks = if (raw.has("tracks")) parseJsonList<ReadAloudBgmTrack>(raw.get("tracks")) else null
            TtsKernel.bgmSave(group, tracks)
        },

        McpTool(
            name = "audiobook_control",
            title = "有声书播放控制",
            description = "有声书（播放音频文件）控制。action：play/pause/resume/stop/prev/next/" +
                "set_speed(speed)/skip_to(chapterIndex)/set_timer(minute)。" +
                "**端侧指令：下发后回读播放态**（不阻塞等待，需确认时再调 audiobook_progress_get）。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf(
                    "speed" to TYPE_NUMBER,
                    "chapterIndex" to TYPE_INTEGER,
                    "minute" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "action" to "播放动作（见说明）",
                    "speed" to "set_speed 的倍速（0.5~3.0，默认 1.0）",
                    "chapterIndex" to "skip_to 的目标章节下标",
                    "minute" to "set_timer 的定时分钟数",
                )
            ),
        ) { args ->
            AudioKernel.audiobookControl(
                action = args.requireStr("action"),
                speed = args.str("speed")?.toFloatOrNull() ?: 1.0f,
                chapterIndex = args.int("chapterIndex", 0),
                minute = args.int("minute", 0),
            )
        },

        McpTool(
            name = "audiobook_progress_get",
            title = "有声书播放态",
            description = "读取有声书播放态与进度（运行/暂停/倍速/定时/章节/播放 URL 已打码）。只读。" +
                "用于回读 audiobook_control 下发的指令效果。",
            domain = MCP_DOMAIN_TTS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AudioKernel.audiobookProgress()
        },
    )

    /** 与 REST 门面同口径：对象或 JSON 字符串均可。 */
    private inline fun <reified T> parseJson(element: JsonElement?): T? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<T>(json).getOrNull()
    }

    /** 对象数组 → 实体列表（元素为 JSON 对象）；空数组返回空列表。 */
    private inline fun <reified T> parseJsonList(element: JsonElement?): List<T>? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonArray<T>(json).getOrNull()
    }
}